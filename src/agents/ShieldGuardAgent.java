package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ShieldGuardAgent — Privacy / encryption gateway to cloud analytics.
 *
 * FSM states (per message):
 *   IDLE  →  DE_IDENTIFY  →  ENCRYPT  →  FORWARD_TO_BIO  →  AWAIT_CONFIRM  →  IDLE
 *
 *
 *  - AES/CBC with random IV instead of insecure ECB mode
 *  - IV prepended to ciphertext (Base64(iv + ciphertext))
 *  - PII stripping via regex covers more fields
 *  - ConversationId correctly threaded for request–reply matching
 *  - Separate MessageTemplate for BioHistorian ACKs prevents cross-contamination
 */
public class ShieldGuardAgent extends Agent {

    // AES-128 — key must be exactly 16 bytes
    private static final String KEY_STR = "SMESDemoKey12345"; // 16 bytes
    private static final String CIPHER_ALGO = "AES/CBC/PKCS5Padding";

    // ── State names ──────────────────────────────────────────────────────────
    private static final String S_IDLE          = "IDLE";
    private static final String S_DE_IDENTIFY   = "DE_IDENTIFY";
    private static final String S_ENCRYPT       = "ENCRYPT";
    private static final String S_FORWARD       = "FORWARD_TO_BIO";
    private static final String S_AWAIT_CONFIRM = "AWAIT_CONFIRM";

    private static final Logger LOG = Logger.getLogger(ShieldGuardAgent.class.getName());

    // ── Request-tracking map  convId → original sender AID ──────────────────
    private final Map<String, AID> pendingSenders = new HashMap<>();

    // ── Per-cycle shared state ───────────────────────────────────────────────
    private ACLMessage currentMsg;
    private String safePayload;
    private String encryptedPayload;
    private String convId;

    // ── BioHistorian AID (lazily resolved) ──────────────────────────────────
    private AID bioAID;

    @Override
    protected void setup() {
        LOG.info("ShieldGuard starting…");
        registerWithDF();

        FSMBehaviour fsm = buildFSM();
        addBehaviour(fsm);

        // Separate behaviour to consume BioHistorian CONFIRMs (may arrive any time)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage confirm = receive(
                        MessageTemplate.MatchPerformative(ACLMessage.CONFIRM));
                if (confirm != null) {
                    handleBioConfirm(confirm);
                } else {
                    block();
                }
            }
        });
    }

    // ── DF helpers ──────────────────────────────────────────────────────────

    private void registerWithDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("shield-guard");
            sd.setName("SMES-ShieldGuard");
            dfd.addServices(sd);
            DFService.register(this, dfd);
            LOG.info("Registered as 'shield-guard'");
        } catch (FIPAException e) {
            LOG.severe("DF registration failed: " + e.getMessage());
        }
    }

    private void lookupBioHistorian() {
        if (bioAID != null) return;
        try {
            DFAgentDescription t = new DFAgentDescription();
            ServiceDescription s = new ServiceDescription();
            s.setType("bio-historian");
            t.addServices(s);
            DFAgentDescription[] r = DFService.search(this, t);
            if (r.length > 0) {
                bioAID = r[0].getName();
                LOG.info("Found BioHistorian: " + bioAID.getLocalName());
            }
        } catch (FIPAException e) {
            LOG.warning("BioHistorian lookup failed: " + e.getMessage());
        }
    }

    // ── FSM construction ────────────────────────────────────────────────────

    private FSMBehaviour buildFSM() {
        FSMBehaviour fsm = new FSMBehaviour(this);

        fsm.registerFirstState(new IdleState(),         S_IDLE);
        fsm.registerState(new DeIdentifyState(),        S_DE_IDENTIFY);
        fsm.registerState(new EncryptState(),           S_ENCRYPT);
        fsm.registerState(new ForwardState(),           S_FORWARD);
        fsm.registerState(new AwaitConfirmState(),      S_AWAIT_CONFIRM);

        // Transitions
        fsm.registerTransition(S_IDLE,          S_DE_IDENTIFY,   0); // message arrived
        fsm.registerTransition(S_IDLE,          S_IDLE,          1); // no message
        fsm.registerTransition(S_DE_IDENTIFY,   S_ENCRYPT,       0);
        fsm.registerTransition(S_ENCRYPT,       S_FORWARD,       0); // success
        fsm.registerTransition(S_ENCRYPT,       S_IDLE,          1); // encrypt failed
        fsm.registerTransition(S_FORWARD,       S_AWAIT_CONFIRM, 0); // forwarded
        fsm.registerTransition(S_FORWARD,       S_IDLE,          1); // bio unavailable
        fsm.registerTransition(S_AWAIT_CONFIRM, S_IDLE,          0);

        return fsm;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FSM STATES
    // ════════════════════════════════════════════════════════════════════════

    /** Wait for an incoming INFORM containing patient data. */
    private class IdleState extends SimpleBehaviour {
        private boolean ready = false;

        @Override
        public void action() {
            ready = false;
            // Accept INFORM or REQUEST that carries patient data
            currentMsg = myAgent.receive(
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
            if (currentMsg != null && currentMsg.getContent() != null
                    && currentMsg.getContent().contains("patient=")) {
                ready = true;
            } else if (currentMsg != null) {
                // unexpected message — discard
                LOG.fine("ShieldGuard discarded unrecognised message: "
                        + currentMsg.getContent());
            } else {
                block(2000);
            }
        }

        @Override public boolean done() { return ready; }
        @Override public int  onEnd()   { return ready ? 0 : 1; }
    }

    /**
     * Strip PII and produce a stable pseudonym per real patient.
     *
     * FIX: Instead of hashing the entire message (which changes every time),
     * we extract the real patient ID and map it to a fixed pseudonym.
     */
    private class DeIdentifyState extends OneShotBehaviour {
        // Map real patient ID → stable pseudonym (persists across messages)
        private final Map<String, String> realToPseudonym = new HashMap<>();

        @Override
        public void action() {
            String raw = currentMsg.getContent();
            LOG.info("De-identifying: " + raw);

            // 1. Extract the real patient ID (e.g., "PT-001", "12121", etc.)
            String realPatientId = extractPatientId(raw);
            if (realPatientId == null) {
                // No patient ID found – keep unchanged as fallback
                safePayload = raw;
                LOG.warning("No patient ID found in message – leaving unchanged.");
                return;
            }

            // 2. Generate or retrieve stable pseudonym for this real patient
            String pseudonym = realToPseudonym.computeIfAbsent(realPatientId,
                    id -> "anon-" + Integer.toHexString(id.hashCode() & 0xFFFF));

            // 3. Replace the original patient= field with the pseudonym
            //    Also remove any other PII fields (name, dob, nhs, ssn)
            safePayload = raw.replaceAll("patient=[^;]+", "patient=" + pseudonym)
                    .replaceAll("(name|dob|nhs|ssn)=[^;]+;?", "");

            LOG.info("De-identified: " + safePayload);
        }

        /** Extracts the value of the "patient=" field from the message. */
        private String extractPatientId(String raw) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("patient=([^;]+)").matcher(raw);
            return m.find() ? m.group(1) : null;
        }

        @Override public int onEnd() { return 0; }
    }

    /** Encrypt with AES/CBC — IV prepended to ciphertext. */
    private class EncryptState extends OneShotBehaviour {
        private int exitCode = 0;

        @Override
        public void action() {
            try {
                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(iv);

                SecretKeySpec keySpec = new SecretKeySpec(KEY_STR.getBytes("UTF-8"), "AES");
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
                byte[] cipherBytes = cipher.doFinal(safePayload.getBytes("UTF-8"));

                // Prepend IV so the recipient can decrypt
                byte[] combined = new byte[iv.length + cipherBytes.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

                encryptedPayload = Base64.getEncoder().encodeToString(combined);
                LOG.info("Encrypted payload length=" + encryptedPayload.length());
                exitCode = 0;
            } catch (Exception e) {
                LOG.severe("Encryption failed: " + e.getMessage());
                encryptedPayload = null;
                exitCode = 1;
            }
        }

        @Override public int onEnd() { return exitCode; }
    }

    /** Forward encrypted payload to BioHistorian. */
    private class ForwardState extends OneShotBehaviour {
        private int exitCode = 0;

        @Override
        public void action() {
            lookupBioHistorian();
            if (bioAID == null) {
                LOG.warning("BioHistorian unavailable — dropping encrypted payload");
                exitCode = 1;
                return;
            }
            convId = getAID().getLocalName() + "-" + System.currentTimeMillis();
            pendingSenders.put(convId, currentMsg.getSender());

            ACLMessage toBio = new ACLMessage(ACLMessage.INFORM);
            toBio.addReceiver(bioAID);
            toBio.setContent(encryptedPayload);
            toBio.setConversationId(convId);
            send(toBio);
            LOG.info("Forwarded encrypted payload to BioHistorian (convId=" + convId + ")");
            exitCode = 0;
        }

        @Override public int onEnd() { return exitCode; }
    }

    /**
     * Wait up to 5 s for a CONFIRM from BioHistorian.
     * (The separate CyclicBehaviour also handles it; this state
     *  simply yields the FSM so it doesn't race ahead.)
     */
    private class AwaitConfirmState extends OneShotBehaviour {
        @Override
        public void action() {
            // The CyclicBehaviour above will handle the CONFIRM when it arrives.
            // Here we just log and move on; if the CONFIRM arrives later that is fine.
            LOG.info("Awaiting BioHistorian CONFIRM for convId=" + convId);
        }
        @Override public int onEnd() { return 0; }
    }

    // ── BioHistorian confirm handler ─────────────────────────────────────────

    private void handleBioConfirm(ACLMessage confirm) {
        String cid = confirm.getConversationId();
        LOG.info("BioHistorian confirmed: " + confirm.getContent()
                + " (convId=" + cid + ")");

        AID originalSender = pendingSenders.remove(cid);
        if (originalSender != null) {
            ACLMessage reply = new ACLMessage(ACLMessage.CONFIRM);
            reply.addReceiver(originalSender);
            reply.setContent("stored:" + confirm.getContent());
            send(reply);
            LOG.info("Relayed CONFIRM to " + originalSender.getLocalName());
        }
    }

    // ── Decrypt utility (for BioHistorian — included here for completeness) ──

    /**
     * Decrypts a Base64-encoded AES/CBC payload with prepended IV.
     * Used by BioHistorianAgent; placed here as a shared utility.
     */
    public static String decrypt(String base64Payload, String keyStr) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Payload);
            byte[] iv       = new byte[16];
            byte[] cipher   = new byte[combined.length - 16];
            System.arraycopy(combined, 0,  iv,     0, 16);
            System.arraycopy(combined, 16, cipher, 0, cipher.length);

            SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes("UTF-8"), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher c = Cipher.getInstance(CIPHER_ALGO);
            c.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return new String(c.doFinal(cipher), "UTF-8");
        } catch (Exception e) {
            return "DECRYPT_ERROR:" + e.getMessage();
        }
    }
}