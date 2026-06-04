package agents;

// ========================= IMPORTS =========================

// JADE core classes
import jade.core.AID;
import jade.core.Agent;

// JADE behaviours
import jade.core.behaviours.CyclicBehaviour;

// JADE ACL messaging
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

// JADE Directory Facilitator (DF)
import jade.domain.DFService;
import jade.domain.FIPAException;

// DF descriptions
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

/**
 * ============================================================
 * DecisionNodeAgent
 * ============================================================
 *
 * This agent acts as the "brain" of the healthcare system.
 *
 * Responsibilities:
 *
 * Role
 * Listens to the Gatekeeper (receives filtered patient data).
 *
 * Decides the system state (NORMAL, EMERGENCY, SENSOR_FAILURE).
 *
 * Sends commands to other agents accordingly.
 *
 * Forwards all data to ShieldGuardAgent for privacy/encryption.
 * 1. Receive filtered sensor data from Gatekeeper
 * 2. Parse the payload
 * 3. Evaluate the medical/system state
 * 4. Trigger actions:
 *      - PowerSaver
 *      - MediNotifier
 *      - ShieldGuard
 * 5. Handle ACK replies separately
 *
 * ============================================================
 */
public class DecisionNodeAgent extends Agent {

    // =========================================================
    // ENUM = FSM STATES
    // =========================================================
    //
    // FSM = Finite State Machine
    //
    // Instead of giant if/else chains,
    // we represent the system as STATES.
    //
    // This is cleaner and scalable.
    //
    // =========================================================

    enum SystemState {
        NORMAL,
        EMERGENCY,
        SENSOR_FAILURE
    }

    // =========================================================
    // CURRENT STATE OF THE SYSTEM
    // =========================================================

    private SystemState currentState = SystemState.NORMAL;

    // =========================================================
    // AIDs OF HELPER AGENTS
    // =========================================================
    //
    // AID = Agent Identifier
    //
    // These are references to other agents.
    //
    // =========================================================

    private AID powerAID;
    private AID mediAID;
    private AID shieldAID;

    // =========================================================
    // AGENT SETUP
    // =========================================================

    @Override
    protected void setup() {

        System.out.println(
                "Agent " + getAID().getName() + " started."
        );

        // =====================================================
        // STEP 1:
        // REGISTER THIS AGENT IN DF
        // =====================================================
        //
        // DF = Directory Facilitator
        //
        //
        // "Yellow Pages for agents"
        //
        // Other agents can search for this service.
        //
        // =====================================================

        try {

            // Create DF description
            DFAgentDescription dfd =
                    new DFAgentDescription();

            // Set agent name
            dfd.setName(getAID());

            // Create service description
            ServiceDescription sd =
                    new ServiceDescription();

            // Type of service
            sd.setType("decision-making");

            // Service name
            sd.setName("SMES-Decision");

            // Attach service to DF description
            dfd.addServices(sd);

            // Register in DF
            DFService.register(this, dfd);

            System.out.println(
                    "DecisionNode registered in DF."
            );

        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // =====================================================
        // STEP 2:
        // ADD GATEKEEPER LISTENER BEHAVIOUR
        // =====================================================
        //
        // This behaviour ONLY processes:
        //
        // - INFORM messages
        // - coming from Gatekeeper
        //
        // =====================================================

        addBehaviour(new GatekeeperListenerBehaviour());

        // =====================================================
        // STEP 3:
        // ADD ACK HANDLER BEHAVIOUR
        // =====================================================
        //
        // This behaviour ONLY processes:
        //
        // - CONFIRM
        // - AGREE
        //
        // replies from helper agents
        //
        // =====================================================

        addBehaviour(new AckHandlerBehaviour());
    }

    // =========================================================
    // GATEKEEPER LISTENER BEHAVIOUR
    // =========================================================

    private class GatekeeperListenerBehaviour
            extends CyclicBehaviour {

        @Override
        public void action() {

            // =================================================
            // MESSAGE TEMPLATE
            // =================================================
            //
            // We ONLY want:
            //
            // INFORM messages
            // FROM gatekeeper
            //
            // =================================================

            MessageTemplate template =
                    MessageTemplate.and(

                            MessageTemplate.MatchPerformative(
                                    ACLMessage.INFORM
                            ),

                            MessageTemplate.MatchSender(
                                    new AID(
                                            "gatekeeper",
                                            AID.ISLOCALNAME
                                    )
                            )
                    );

            // Receive ONLY matching messages
            ACLMessage msg = receive(template);

            // =================================================
            // IF MESSAGE EXISTS
            // =================================================

            if (msg != null) {

                System.out.println(
                        "\n=============================="
                );

                System.out.println(
                        "DecisionNode received message"
                );

                System.out.println(
                        "FROM: " +
                                msg.getSender().getLocalName()
                );

                System.out.println(
                        "CONTENT: " +
                                msg.getContent()
                );

                System.out.println(
                        "=============================="
                );

                // =============================================
                // STEP 1:
                // GET PAYLOAD
                // =============================================

                String content = msg.getContent();

                // =============================================
                // STEP 2:
                // DEFAULT VALUES
                // =============================================
                //
                // If parsing fails,
                // we still avoid null crashes.
                //
                // =============================================

                String patientId = "UNKNOWN";

                String hr = "0";

                String quality = "BAD";

                String status = "DROP";

                String ts =
                        String.valueOf(
                                System.currentTimeMillis()
                        );

                // =============================================
                // STEP 3:
                // PARSE PAYLOAD
                // =============================================
                //
                // Example payload:
                //
                // patient=PT-009;
                // HR=88;
                // quality=GOOD;
                // status=NORMAL;
                // ts=123456
                //
                // split(";")
                //
                // becomes:
                //
                // [patient=PT-009]
                // [HR=88]
                // [quality=GOOD]
                // ...
                //
                // =============================================

                if (content != null) {

                    for (String part : content.split(";")) {

                        // patient=PT-009
                        if (part.startsWith("patient=")) {

                            patientId =
                                    part.substring(8);
                        }

                        // HR=88
                        else if (part.startsWith("HR=")) {

                            hr =
                                    part.substring(3);
                        }

                        // quality=GOOD
                        else if (part.startsWith("quality=")) {

                            quality =
                                    part.substring(8);
                        }

                        // status=NORMAL
                        else if (part.startsWith("status=")) {

                            status =
                                    part.substring(7);
                        }

                        // ts=123456
                        else if (part.startsWith("ts=")) {

                            ts =
                                    part.substring(3);
                        }
                    }
                }

                // =============================================
                // PRINT PARSED VALUES
                // =============================================

                System.out.println("\nParsed values:");

                System.out.println(
                        "Patient = " + patientId
                );

                System.out.println(
                        "HR = " + hr
                );

                System.out.println(
                        "Quality = " + quality
                );

                System.out.println(
                        "Status = " + status
                );

                // =============================================
                // STEP 4:
                // FIND HELPER AGENTS
                // =============================================
                //
                // We search DF ONLY if AID is null.
                //
                // This avoids repeated expensive searches.
                //
                // =============================================

                discoverHelpers();

                // =============================================
                // STEP 5:
                // FSM DECISION LOGIC
                // =============================================

                SystemState newState;

                // NORMAL CASE
                if ("GOOD".equals(quality)
                        && "NORMAL".equals(status)) {

                    newState =
                            SystemState.NORMAL;
                }

                // SENSOR FAILURE
                else if ("BAD".equals(quality)) {

                    newState =
                            SystemState.SENSOR_FAILURE;
                }

                // EMERGENCY
                else {

                    newState =
                            SystemState.EMERGENCY;
                }

                // =============================================
                // STEP 6:
                // DETECT STATE TRANSITION
                // =============================================

                if (newState != currentState) {

                    System.out.println(
                            "\nSTATE CHANGE:"
                    );

                    System.out.println(
                            currentState
                                    + " ---> "
                                    + newState
                    );

                    currentState = newState;
                }

                // =============================================
                // STEP 7:
                // EXECUTE ACTIONS BASED ON FSM STATE
                // =============================================

                switch (currentState) {

                    // =========================================
                    // NORMAL STATE
                    // =========================================

                    case NORMAL:

                        notifyPowerSaver("NORMAL");

                        break;

                    // =========================================
                    // EMERGENCY STATE
                    // =========================================

                    case EMERGENCY:

                        notifyMediNotifier(
                                patientId
                        );

                        notifyPowerSaver(
                                "EMERGENCY"
                        );

                        break;

                    // =========================================
                    // SENSOR FAILURE STATE
                    // =========================================

                    case SENSOR_FAILURE:

                        notifyPowerSaver(
                                "SENSOR_FAILURE"
                        );

                        break;
                }

                // =============================================
                // STEP 8:
                // ALWAYS FORWARD TO SHIELDGUARD
                // =============================================
                //
                // ShieldGuard stores/audits data.
                //
                // =============================================

                notifyShieldGuard(
                        patientId,
                        hr,
                        quality,
                        status,
                        ts
                );
            }

            // =================================================
            // NO MESSAGE
            // =================================================

            else {

                block();
            }
        }
    }

    // =========================================================
    // ACK HANDLER BEHAVIOUR
    // =========================================================
    //
    // Handles:
    //
    // - AGREE
    // - CONFIRM
    //
    // separately from Gatekeeper traffic.
    //
    // =========================================================

    private class AckHandlerBehaviour
            extends CyclicBehaviour {

        @Override
        public void action() {

            // Template for ACK replies
            MessageTemplate ackTemplate =
                    MessageTemplate.or(

                            MessageTemplate
                                    .MatchPerformative(
                                            ACLMessage.CONFIRM
                                    ),

                            MessageTemplate
                                    .MatchPerformative(
                                            ACLMessage.AGREE
                                    )
                    );

            ACLMessage ack =
                    receive(ackTemplate);

            // If ACK exists
            if (ack != null) {

                System.out.println(
                        "\nACK RECEIVED"
                );

                System.out.println(
                        "FROM: "
                                + ack.getSender()
                                .getLocalName()
                );

                System.out.println(
                        "CONTENT: "
                                + ack.getContent()
                );
            }

            // No ACK
            else {

                block();
            }
        }
    }

    // =========================================================
    // DF DISCOVERY METHOD
    // =========================================================
    //
    // Finds helper agents from DF.
    //
    // =========================================================

    private void discoverHelpers() {

        try {

            // =============================================
            // FIND POWERSAVER
            // =============================================

            if (powerAID == null) {

                DFAgentDescription template =
                        new DFAgentDescription();

                ServiceDescription sd =
                        new ServiceDescription();

                sd.setType("power-saving");

                template.addServices(sd);

                DFAgentDescription[] result =
                        DFService.search(this, template);

                if (result.length > 0) {

                    powerAID =
                            result[0].getName();

                    System.out.println(
                            "Found PowerSaver"
                    );
                }
            }

            // =============================================
            // FIND MEDINOTIFIER
            // =============================================

            if (mediAID == null) {

                DFAgentDescription template =
                        new DFAgentDescription();

                ServiceDescription sd =
                        new ServiceDescription();

                sd.setType("medi-notifier");

                template.addServices(sd);

                DFAgentDescription[] result =
                        DFService.search(this, template);

                if (result.length > 0) {

                    mediAID =
                            result[0].getName();

                    System.out.println(
                            "Found MediNotifier"
                    );
                }
            }

            // =============================================
            // FIND SHIELDGUARD
            // =============================================

            if (shieldAID == null) {

                DFAgentDescription template =
                        new DFAgentDescription();

                ServiceDescription sd =
                        new ServiceDescription();

                sd.setType("shield-guard");

                template.addServices(sd);

                DFAgentDescription[] result =
                        DFService.search(this, template);

                if (result.length > 0) {

                    shieldAID =
                            result[0].getName();

                    System.out.println(
                            "Found ShieldGuard"
                    );
                }
            }

        } catch (FIPAException e) {

            e.printStackTrace();
        }
    }

    // =========================================================
    // SEND TO POWERSAVER
    // =========================================================

    private void notifyPowerSaver(
            String state
    ) {

        if (powerAID != null) {

            ACLMessage msg =
                    new ACLMessage(
                            ACLMessage.INFORM
                    );

            msg.addReceiver(powerAID);

            msg.setContent(
                    "state=" + state
            );

            send(msg);

            System.out.println(
                    "Sent to PowerSaver: "
                            + state
            );
        }
    }

    // =========================================================
    // SEND TO MEDINOTIFIER
    // =========================================================

    private void notifyMediNotifier(
            String patientId
    ) {

        if (mediAID != null) {

            ACLMessage msg =
                    new ACLMessage(
                            ACLMessage.REQUEST
                    );

            msg.addReceiver(mediAID);

            msg.setContent(
                    "alert=EMERGENCY;"
                            + "patient="
                            + patientId
            );

            send(msg);

            System.out.println(
                    "Emergency alert sent."
            );
        }
    }

    // =========================================================
    // SEND TO SHIELDGUARD
    // =========================================================

    private void notifyShieldGuard(

            String patientId,
            String hr,
            String quality,
            String status,
            String ts
    ) {

        if (shieldAID != null) {

            ACLMessage msg =
                    new ACLMessage(
                            ACLMessage.INFORM
                    );

            msg.addReceiver(shieldAID);

            // Build payload dynamically
            String payload =

                    "patient=" + patientId
                            + ";HR=" + hr
                            + ";quality=" + quality
                            + ";status=" + status
                            + ";ts=" + ts;

            msg.setContent(payload);

            send(msg);

            System.out.println(
                    "Forwarded to ShieldGuard:"
            );

            System.out.println(payload);
        }
    }

    // =========================================================
    // TAKE DOWN
    // =========================================================
    //
    // Called when agent terminates.
    //
    // =========================================================

    @Override
    protected void takeDown() {

        try {

            DFService.deregister(this);

        } catch (FIPAException e) {

            e.printStackTrace();
        }

        System.out.println(
                "DecisionNode terminated."
        );
    }
}