package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

public class PowerSaverAgent extends Agent {

    private AID vitalAID;

    // Tracks the last rate command we sent to VitalSense.
    // null = nothing sent yet, so the first message always fires.
    // This prevents spamming VitalSense with the same command every cycle.
    private String lastSentRate = null;

    @Override
    protected void setup() {
        System.out.println("power Saver Agent starting ");
        System.out.println("PowerSaver starting...");

        // Register in DF so DecisionNode can find this agent by type "power-saving"
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("power-saving");  // DecisionNode searches for EXACTLY this string
            sd.setName("SMES-PowerSaver");
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.println("powersaver registered as power-saving");
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
                    System.out.println("powersaver got " + type + " from " + sender
                            + " content=" + msg.getContent());

                    // Parse the state sent by DecisionNode.
                    // Format: "state=NORMAL" | "state=EMERGENCY" | "state=SENSOR_FAILURE"
                    String state = "NORMAL";
                    if (msg.getContent() != null && msg.getContent().startsWith("state=")) {
                        state = msg.getContent().substring(6).trim();
                    }

                    // Map each state to a sampling rate command for VitalSense.
                    //
                    // NORMAL         → LOW    (4000 ms) — patient stable, save battery
                    // EMERGENCY      → HIGH   (1000 ms) — critical, monitor as fast as possible
                    // SENSOR_FAILURE → MEDIUM (2000 ms) — sensor bad, retry faster to detect
                    //                                      recovery, but no need for full speed
                    //
                    // FIX: original code used ternary (NORMAL ? LOW : HIGH) so SENSOR_FAILURE
                    // was treated identically to EMERGENCY. This caused HIGH rate to fire on
                    // every bad reading even when there was no clinical emergency.
                    String rate;
                    switch (state) {
                        case "EMERGENCY":
                            rate = "HIGH";
                            break;
                        case "SENSOR_FAILURE":
                            rate = "MEDIUM";
                            break;
                        default:
                            // NORMAL and any unknown state default to LOW
                            rate = "LOW";
                            break;
                    }

                    // Find VitalSense in the DF once and cache its AID.
                    // We only search again if the previous lookup failed.
                    if (vitalAID == null) {
                        try {
                            DFAgentDescription t = new DFAgentDescription();
                            ServiceDescription s = new ServiceDescription();
                            s.setType("vital-sensing"); // VitalSense registers with this type
                            t.addServices(s);
                            DFAgentDescription[] r = DFService.search(myAgent, t);
                            if (r.length > 0) vitalAID = r[0].getName();
                        } catch (Exception e) {
                            System.out.println("powersaver: DF lookup failed - " + e.getMessage());
                        }
                    }

                    // Only send a new rate command to VitalSense when the rate
                    // has actually changed. Avoids flooding VitalSense with
                    // identical commands on every single message from DecisionNode.
                    if (vitalAID != null) {
                        if (rate.equals(lastSentRate)) {
                            System.out.println("powersaver: rate=" + rate
                                    + " already active, no command sent");
                        } else {
                            // Rate changed — send the new command
                            ACLMessage toVital = new ACLMessage(ACLMessage.REQUEST);
                            toVital.addReceiver(vitalAID);
                            toVital.setContent("rate=" + rate);
                            send(toVital);
                            lastSentRate = rate; // remember what we last sent
                            System.out.println("powersaver to vital : REQUEST rate=" + rate
                                    + "  (state changed to " + state + ")");
                        }
                    } else {
                        System.out.println("powersaver: VitalSense not found in DF, skipping rate command");
                    }

                    // Always reply CONFIRM so DecisionNode knows we received the message
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.CONFIRM);
                    reply.setContent("ACK");
                    send(reply);
                } else {
                    block();
                }
            }
        });
    }
}