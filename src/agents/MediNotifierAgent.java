package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import java.util.HashMap;
import java.util.Map;

public class MediNotifierAgent extends Agent {

    // FIX: cooldown map — stores the last time we fired an alert per patient.
    // Without this, DecisionNode's continuous EMERGENCY messages (one per reading)
    // caused hundreds of identical alerts per minute — the infinite spam in the logs.
    private final Map<String, Long> lastAlertTime = new HashMap<>();

    // How long to wait before re-alerting for the same patient (2 minutes = 120,000 ms).
    // Change this value to tune sensitivity vs. alarm fatigue.
    private static final long COOLDOWN_MS = 2 * 60 * 1000;

    @Override
    protected void setup() {
        System.out.println("MediNotifier starting...");

        // Register in the DF so DecisionNode can find this agent by type "medi-notifier"
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("medi-notifier"); // DecisionNode looks for exactly this type
            sd.setName("SMES-MediNotifier");
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.println("medinotifier registered as medi-notifier");
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    String sender = msg.getSender().getLocalName();
                    String type = ACLMessage.getPerformative(msg.getPerformative());
                    System.out.println("medinotifier got " + type + " from " + sender + " content=" + msg.getContent());

                    // Only act on REQUEST messages that contain "EMERGENCY".
                    // This is the alarm-fatigue guard from the original design —
                    // NORMAL and ALERT states do NOT trigger notifications.
                    if (msg.getPerformative() == ACLMessage.REQUEST
                            && msg.getContent() != null
                            && msg.getContent().contains("EMERGENCY")) {

                        // Parse patient ID from content like "alert=EMERGENCY;patient=PT-002"
                        String patient = "UNKNOWN";
                        for (String part : msg.getContent().split(";")) {
                            if (part.startsWith("patient=")) {
                                patient = part.substring(8).trim();
                            }
                        }

                        long now = System.currentTimeMillis();

                        // FIX: check cooldown before firing.
                        // If we already alerted for this patient recently, suppress it.
                        // This is what stops the infinite loop — the original fired on
                        // every single message with no memory of previous alerts.
                        if (lastAlertTime.containsKey(patient)
                                && (now - lastAlertTime.get(patient)) < COOLDOWN_MS) {

                            long secondsLeft = (lastAlertTime.get(patient) + COOLDOWN_MS - now) / 1000;
                            System.out.println("medinotifier: alert for " + patient
                                    + " suppressed — cooldown active (" + secondsLeft + "s remaining)");

                        } else {
                            // Cooldown expired or first alert for this patient — fire it
                            lastAlertTime.put(patient, now); // record the time we fired
                            System.out.println(">>> EMERGENCY ALERT - patient=" + patient);
                            System.out.println(">>> SMS to Dr.Milad: Patient " + patient + " in critical condition");
                            System.out.println(">>> Push to Nurse Miano: Immediate attention required");
                        }
                    }

                    // Always reply CONFIRM so DecisionNode FSM can advance to next state.
                    // We reply regardless of whether we actually fired an alert.
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.CONFIRM);
                    reply.setContent("notified");
                    send(reply);
                    System.out.println("medinotifier to " + sender + " : CONFIRM notified");
                } else {
                    block();
                }
            }
        });
    }
}