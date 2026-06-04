package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * ============================================================
 * BioHistorianAgent – Hospital Intelligence Layer (HIL)
 * ============================================================
 *
 * PURPOSE:
 *   Acts as the central analytics and audit agent for the SMES healthcare system.
 *   Receives encrypted patient data from ShieldGuard, decrypts it, computes
 *   real-time clinical analytics (trends, risk scores, alerts), and writes
 *   multiple output files for audit, analytics, alerts, and patient profiles.
 *
 * INPUT:
 *   - Receives ACLMessage.INFORM from ShieldGuardAgent.
 *   - Message content is Base64-encoded AES/CBC encrypted payload.
 *   - The plaintext after decryption is a semicolon-separated string:
 *        patient=<pseudonym>;HR=<bpm>;quality=<GOOD/BAD>;status=<NORMAL/WARN/DROP>;ts=<timestamp>
 *
 * OUTPUT (FILES):
 *   1. hil_store.txt      – Encrypted audit log (timestamp|encrypted payload) for compliance.
 *   2. hil_analytics.txt  – Decrypted, human-readable analytics per reading (always written).
 *   3. hil_alerts.txt     – Only written when predictive or alert conditions fire.
 *   4. hil_profiles.txt   – Overwritten each reading; summary of all patients' latest stats.
 *
 * OUTPUT (ACL):
 *   - Sends ACLMessage.CONFIRM back to ShieldGuard with content "HIL_STORED:<patient>:<readingCount>"
 *
 * COMMUNICATING AGENTS:
 *   - Receives FROM: ShieldGuardAgent (type "shield-guard", registered in DF)
 *   - Sends TO:      ShieldGuardAgent (as a reply, CONFIRM)
 *
 * DEPENDENCIES:
 *   - AES key "SMESDemoKey12345" must match ShieldGuardAgent's encryption key.
 *   - Requires write permission in current working directory for the four output files.
 *
 * ============================================================
 */
public class BioHistorianAgent extends Agent {

    // =========================================================
    // 1. CRYPTO CONSTANTS – must match ShieldGuardAgent
    // =========================================================
    private static final String KEY_STR     = "SMESDemoKey12345"; // 16-byte AES key
    private static final String CIPHER_ALGO = "AES/CBC/PKCS5Padding"; // AES with CBC mode

    // =========================================================
    // 2. PATIENT PROFILE & DEDUPLICATION SETTINGS
    // =========================================================
    private static final int  MAX_HISTORY     = 50;   // keep last 50 readings per patient
    private static final long DEDUP_WINDOW_MS = 500;  // ignore duplicate readings within 500ms

    // =========================================================
    // 3. RISK SCORING THRESHOLDS
    // =========================================================
    private static final int    HR_CRITICAL_LOW  = 50;   // bpm – adds 3 risk points
    private static final int    HR_CRITICAL_HIGH = 120;  // bpm – adds 3 risk points
    private static final int    HR_WARN_LOW      = 60;   // bpm – adds 1 risk point
    private static final int    HR_WARN_HIGH     = 100;  // bpm – adds 1 risk point
    private static final double SLOPE_THRESHOLD  = 1.5;  // bpm/reading – adds 2 risk points
    private static final double HRV_THRESHOLD    = 15.0; // bpm (std dev) – adds 2 risk points
    private static final double Z_THRESHOLD      = 2.5;  // adds 2 risk points
    private static final int    SUDDEN_DELTA     = 20;   // bpm change in 2 readings – adds 3 risk points

    // =========================================================
    // 4. LOGGING & DATE FORMAT
    // =========================================================
    private static final Logger LOG = Logger.getLogger(BioHistorianAgent.class.getName());
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // =========================================================
    // 5. INTERNAL DATA STRUCTURES
    // =========================================================

    /** One single reading (heart rate, timestamp, status from Gatekeeper) */
    private static class Reading {
        final int    hr;
        final long   ts;
        final String status;
        Reading(int hr, long ts, String status) {
            this.hr = hr; this.ts = ts; this.status = status;
        }
    }

    /** All readings for one patient (sliding window up to MAX_HISTORY) */
    private static class PatientProfile {
        final Deque<Reading> history = new ArrayDeque<>(); // chronological order
        long lastTs = -1; // timestamp of last received reading (for deduplication)

        void addReading(Reading r) {
            history.addLast(r);
            if (history.size() > MAX_HISTORY) history.pollFirst(); // keep only last 50
            lastTs = r.ts;
        }

        boolean isDuplicate(long ts) {
            return lastTs >= 0 && (ts - lastTs) < DEDUP_WINDOW_MS;
        }

        List<Reading> asList() { return new ArrayList<>(history); }
        int size() { return history.size(); }
    }

    /** Parsed and decrypted fields from the incoming message */
    private static class ParsedReading {
        String patient = "unknown";
        int    hr      = -1;
        String status  = "NORMAL";
    }

    /** Computed analytics for a single reading */
    private static class Analytics {
        double avg5, avg20;     // moving averages over last 5 and 20 readings
        double slope;           // linear trend over last 10 readings (bpm per reading)
        double hrv;             // heart rate variability = standard deviation over last 10 readings
        double z;               // z-score of current HR relative to last 20 readings
        boolean suddenDrop;     // HR dropped by ≥20 bpm in 2 readings
        boolean suddenRise;     // HR rose by ≥20 bpm in 2 readings
        int    riskScore;       // 0-10 composite risk score
        String riskLevel;       // LOW (0-2), MEDIUM (3-5), HIGH (6-10)
    }

    // =========================================================
    // 6. AGENT STATE – stores profiles for all patients
    // =========================================================
    private final Map<String, PatientProfile> profiles = new HashMap<>();

    // File writers (opened once, closed on takeDown)
    private BufferedWriter auditWriter;     // hil_store.txt
    private BufferedWriter analyticsWriter; // hil_analytics.txt
    private BufferedWriter alertWriter;     // hil_alerts.txt

    // =========================================================
    // 7. JADE LIFECYCLE METHODS
    // =========================================================

    @Override
    protected void setup() {
        LOG.info("BioHistorian starting...");
        registerWithDF();   // advertise service "bio-historian" so ShieldGuard can find us
        openFiles();        // create/append to output files
        addBehaviour(new ReceiveBehaviour()); // main cyclic behaviour to listen for messages
    }

    @Override
    protected void takeDown() {
        closeFiles();
        LOG.info("BioHistorian shut down cleanly.");
    }

    // =========================================================
    // 8. DF REGISTRATION – tells the Directory Facilitator we exist
    // =========================================================
    private void registerWithDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("bio-historian");      // service type that ShieldGuard searches for
            sd.setName("SMES-BioHistorian");
            dfd.addServices(sd);
            DFService.register(this, dfd);
            LOG.info("Registered as 'bio-historian'");
        } catch (FIPAException e) {
            LOG.severe("DF registration failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 9. FILE MANAGEMENT
    // =========================================================
    private void openFiles() {
        auditWriter     = openWriter("hil_store.txt",     true);
        analyticsWriter = openWriter("hil_analytics.txt", true);
        alertWriter     = openWriter("hil_alerts.txt",    true);
        LOG.info("All HIL output files opened.");
    }

    private BufferedWriter openWriter(String path, boolean append) {
        try {
            return new BufferedWriter(new FileWriter(path, append));
        } catch (IOException e) {
            LOG.severe("Cannot open " + path + ": " + e.getMessage());
            return null;
        }
    }

    private void writeLine(BufferedWriter w, String line) {
        if (w == null) return;
        try { w.write(line); w.newLine(); w.flush(); }
        catch (IOException e) { LOG.warning("File write error: " + e.getMessage()); }
    }

    private void closeFiles() {
        for (BufferedWriter w : new BufferedWriter[]{auditWriter, analyticsWriter, alertWriter}) {
            if (w == null) continue;
            try { w.close(); } catch (IOException e) { /* best effort */ }
        }
    }

    // =========================================================
    // 10. PROFILE SNAPSHOT – overwrites hil_profiles.txt with current patient summaries
    // =========================================================
    private void writeProfileSnapshot() {
        try (BufferedWriter w = new BufferedWriter(new FileWriter("hil_profiles.txt", false))) {
            w.write("=== SMES Patient Profile Snapshot ===");
            w.newLine();
            w.write("Generated: " + SDF.format(new Date()));
            w.newLine();
            w.write("Total patients tracked: " + profiles.size());
            w.newLine();
            w.write("--------------------------------------");
            w.newLine();

            for (Map.Entry<String, PatientProfile> entry : profiles.entrySet()) {
                String pid = entry.getKey();
                PatientProfile p = entry.getValue();
                List<Reading> hist = p.asList();

                double avg5  = movingAvg(hist, 5);
                double avg20 = movingAvg(hist, 20);
                double hrv   = stdDev(hist, 10);
                double slope = linearSlope(hist, 10);
                int latestHr = hist.isEmpty() ? 0 : hist.get(hist.size() - 1).hr;

                w.write(String.format("Patient : %s", pid));
                w.newLine();
                w.write(String.format("  Readings stored : %d / %d", hist.size(), MAX_HISTORY));
                w.newLine();
                w.write(String.format("  Latest HR       : %d bpm", latestHr));
                w.newLine();
                w.write(String.format("  Avg (5-reading) : %.1f bpm", avg5));
                w.newLine();
                w.write(String.format("  Avg (20-reading): %.1f bpm", avg20));
                w.newLine();
                w.write(String.format("  HRV (std-dev)   : %.2f bpm", hrv));
                w.newLine();
                w.write(String.format("  Trend slope     : %.2f bpm/reading", slope));
                w.newLine();
                w.write("--------------------------------------");
                w.newLine();
            }
            w.flush();
        } catch (IOException e) {
            LOG.warning("Profile snapshot write failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 11. MAIN BEHAVIOUR – receives, decrypts, processes, replies
    // =========================================================
    private class ReceiveBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            // Wait for any incoming ACL message (no template – we only expect messages from ShieldGuard)
            ACLMessage msg = receive();
            if (msg == null) { block(); return; }

            String encrypted = msg.getContent();
            if (encrypted == null || encrypted.isEmpty()) {
                LOG.warning("Empty message received — discarding.");
                return;
            }

            // Step 1: Persist encrypted record immediately (audit log, HIPAA-compliant)
            long now = System.currentTimeMillis();
            writeLine(auditWriter, now + "|" + encrypted);

            // Step 2: Decrypt using AES/CBC (same key as ShieldGuard)
            String plain = decrypt(encrypted);
            if (plain == null) {
                LOG.warning("Decryption failed — encrypted record saved, analytics skipped.");
                return;
            }

            // Step 3: Parse the plaintext into patient, HR, status
            ParsedReading parsed = parse(plain);
            if (parsed == null) {
                LOG.warning("Parse failed for payload: " + plain);
                return;
            }

            // Step 4: Retrieve or create patient profile, check for duplicate (within 500ms)
            PatientProfile profile = profiles.computeIfAbsent(
                    parsed.patient, k -> new PatientProfile());

            if (profile.isDuplicate(now)) {
                LOG.fine("Duplicate reading for " + parsed.patient + " — skipped.");
                sendConfirm(msg, parsed.patient, profile.size());
                return;
            }

            // Step 5: Add reading to patient's history
            profile.addReading(new Reading(parsed.hr, now, parsed.status));
            List<Reading> hist = profile.asList();

            // Step 6: Compute analytics (trends, risk score, alerts)
            Analytics a = computeAnalytics(hist, parsed.hr);

            // Step 7: Write to hil_analytics.txt (every reading)
            writeAnalyticsEntry(parsed, hist.size(), a, now);

            // Step 8: Write to hil_alerts.txt (only when conditions fire)
            writeAlertEntries(parsed.patient, a, parsed.hr, now);

            // Step 9: Overwrite hil_profiles.txt with current snapshot of all patients
            writeProfileSnapshot();

            // Step 10: Send CONFIRM back to ShieldGuard with storage confirmation
            sendConfirm(msg, parsed.patient, profile.size());
        }
    }

    // =========================================================
    // 12. FILE WRITERS – analytics and alerts
    // =========================================================

    /**
     * Writes a human-readable analytics record for every reading.
     * This is the main detailed log you see in the console output.
     */
    private void writeAnalyticsEntry(ParsedReading p, int readingCount, Analytics a, long ts) {
        String timestamp = SDF.format(new Date(ts));
        writeLine(analyticsWriter,
                String.format("[%s] patient=%-14s | hr=%3d bpm | readings=%d",
                        timestamp, p.patient, p.hr, readingCount));
        writeLine(analyticsWriter,
                String.format("  avg5=%.1f  avg20=%.1f  slope=%+.2f bpm/rdg  HRV=%.2f  z=%+.2f",
                        a.avg5, a.avg20, a.slope, a.hrv, a.z));
        writeLine(analyticsWriter,
                String.format("  RISK=%-6s (%d/10)  suddenDrop=%-5b  suddenRise=%-5b  status=%s",
                        a.riskLevel, a.riskScore, a.suddenDrop, a.suddenRise, p.status));
        writeLine(analyticsWriter, "");
    }

    /**
     * Writes to hil_alerts.txt only when a medical event is detected.
     * Avoids cluttering the log with normal readings.
     */
    private void writeAlertEntries(String patient, Analytics a, int hr, long ts) {
        String timestamp = SDF.format(new Date(ts));

        if (a.suddenDrop)
            writeLine(alertWriter, String.format(
                    "[%s] ALERT      | patient=%-14s | SUDDEN HR DROP  | hr=%d bpm",
                    timestamp, patient, hr));

        if (a.suddenRise)
            writeLine(alertWriter, String.format(
                    "[%s] ALERT      | patient=%-14s | SUDDEN HR RISE  | hr=%d bpm",
                    timestamp, patient, hr));

        if (a.slope < -1.2 && a.avg5 < 68)
            writeLine(alertWriter, String.format(
                    "[%s] PREDICTIVE | patient=%-14s | Trending toward BRADYCARDIA | avg5=%.1f slope=%.2f",
                    timestamp, patient, a.avg5, a.slope));

        if (a.slope > 1.2 && a.avg5 > 92)
            writeLine(alertWriter, String.format(
                    "[%s] PREDICTIVE | patient=%-14s | Trending toward TACHYCARDIA | avg5=%.1f slope=%.2f",
                    timestamp, patient, a.avg5, a.slope));

        if (a.hrv > 18)
            writeLine(alertWriter, String.format(
                    "[%s] PREDICTIVE | patient=%-14s | High HRV — possible ARRHYTHMIA risk | HRV=%.2f",
                    timestamp, patient, a.hrv));

        if (a.riskLevel.equals("HIGH"))
            writeLine(alertWriter, String.format(
                    "[%s] REVIEW     | patient=%-14s | HIGH RISK SCORE %d/10 — flagged for clinician review",
                    timestamp, patient, a.riskScore));
    }

    // =========================================================
    // 13. PARSING – extracts fields from the decrypted string
    // =========================================================
    private ParsedReading parse(String plain) {
        ParsedReading r = new ParsedReading();
        try {
            for (String part : plain.split(";")) {
                if      (part.startsWith("patient=")) r.patient = part.substring(8);
                else if (part.startsWith("HR="))      r.hr      = Integer.parseInt(part.substring(3).trim());
                else if (part.startsWith("status="))  r.status  = part.substring(7);
            }
        } catch (NumberFormatException e) {
            LOG.warning("HR parse error in: " + plain + " — " + e.getMessage());
            return null;
        }
        if (r.hr < 0) { LOG.warning("No HR field in: " + plain); return null; }
        return r;
    }

    // =========================================================
    // 14. ANALYTICS ENGINE – computes all statistics and risk score
    // =========================================================
    private Analytics computeAnalytics(List<Reading> hist, int currentHr) {
        Analytics a = new Analytics();
        a.avg5  = movingAvg(hist, 5);
        a.avg20 = movingAvg(hist, 20);
        a.slope = linearSlope(hist, 10);
        a.hrv   = stdDev(hist, 10);
        a.z     = zScore(hist, currentHr);

        int n = hist.size();
        // sudden change = difference between current and reading 2 steps ago ≥20 bpm
        a.suddenDrop = n >= 3 && (hist.get(n-1).hr - hist.get(n-3).hr) < -SUDDEN_DELTA;
        a.suddenRise = n >= 3 && (hist.get(n-1).hr - hist.get(n-3).hr) >  SUDDEN_DELTA;

        // Risk scoring (0-10)
        int risk = 0;
        if      (currentHr < HR_CRITICAL_LOW || currentHr > HR_CRITICAL_HIGH) risk += 3;
        else if (currentHr < HR_WARN_LOW     || currentHr > HR_WARN_HIGH)     risk += 1;
        if (Math.abs(a.slope) > SLOPE_THRESHOLD) risk += 2;
        if (a.hrv  > HRV_THRESHOLD)              risk += 2;
        if (Math.abs(a.z) > Z_THRESHOLD)         risk += 2;
        if (a.suddenDrop || a.suddenRise)        risk += 3;

        a.riskScore = Math.min(risk, 10);
        a.riskLevel = a.riskScore <= 2 ? "LOW" : a.riskScore <= 5 ? "MEDIUM" : "HIGH";
        return a;
    }

    // =========================================================
    // 15. CONFIRM REPLY – tells ShieldGuard the data was stored
    // =========================================================
    private void sendConfirm(ACLMessage original, String patient, int count) {
        ACLMessage reply = original.createReply();
        reply.setPerformative(ACLMessage.CONFIRM);
        reply.setContent("HIL_STORED:" + patient + ":" + count);
        send(reply);
    }

    // =========================================================
    // 16. DECRYPTION – AES/CBC with IV prepended
    // =========================================================
    private String decrypt(String base64Payload) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Payload);
            if (combined.length < 17) {
                LOG.warning("Payload too short: " + combined.length + " bytes");
                return null;
            }
            byte[] iv         = Arrays.copyOfRange(combined, 0,  16);
            byte[] ciphertext = Arrays.copyOfRange(combined, 16, combined.length);

            SecretKeySpec keySpec = new SecretKeySpec(KEY_STR.getBytes("UTF-8"), "AES");
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), "UTF-8");
        } catch (Exception e) {
            LOG.severe("Decryption error: " + e.getMessage());
            return null;
        }
    }

    // =========================================================
    // 17. STATISTICS HELPERS
    // =========================================================

    /** Moving average of last n readings (or all if fewer) */
    private double movingAvg(List<Reading> h, int n) {
        int start = Math.max(0, h.size() - n);
        double sum = 0; int count = 0;
        for (int i = start; i < h.size(); i++) { sum += h.get(i).hr; count++; }
        return count == 0 ? 0 : sum / count;
    }

    /** Linear slope (least squares) over last n readings */
    private double linearSlope(List<Reading> h, int n) {
        int start = Math.max(0, h.size() - n);
        int m = h.size() - start;
        if (m < 3) return 0;
        double sx = 0, sy = 0, sxy = 0, sx2 = 0;
        for (int i = 0; i < m; i++) {
            double x = i, y = h.get(start + i).hr;
            sx += x; sy += y; sxy += x*y; sx2 += x*x;
        }
        double den = m * sx2 - sx * sx;
        return den == 0 ? 0 : (m * sxy - sx * sy) / den;
    }

    /** Standard deviation (sample) over last n readings */
    private double stdDev(List<Reading> h, int n) {
        int start = Math.max(0, h.size() - n);
        double mean = movingAvg(h, n);
        double sumSq = 0; int count = 0;
        for (int i = start; i < h.size(); i++) {
            double d = h.get(i).hr - mean; sumSq += d*d; count++;
        }
        return count < 2 ? 0 : Math.sqrt(sumSq / (count - 1));
    }

    /** Z-score = (currentHR - mean20) / stdDev20 */
    private double zScore(List<Reading> h, int hr) {
        double mean = movingAvg(h, 20);
        double sd   = stdDev(h, 20);
        return sd == 0 ? 0 : (hr - mean) / sd;
    }
}