package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;

import jade.domain.DFService;
import jade.domain.FIPAException;

import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import jade.lang.acl.ACLMessage;

public class GatewayGatekeeperAgent extends Agent {
   // Function	Description
    //Receive	Listens for INFORM messages from VitalSense (raw patient=PT-001;HR=75).
    //Validate	Checks if HR is within a physiological range (40–180 bpm).
   // Annotate	Adds quality (GOOD/BAD) and status (NORMAL/WARN/DROP) based on HR value.
   // Forward	Sends the enriched data to the DecisionNode.
    //Acknowledge	Replies CONFIRM to VitalSense so it knows the data was received.
    private AID decisionAID;

    @Override
    protected void setup() {

        System.out.println("Gatekeeper started");

        // REGISTER
        try {

            DFAgentDescription dfd =
                    new DFAgentDescription();

            dfd.setName(getAID());

            ServiceDescription sd =
                    new ServiceDescription();

            sd.setType("gateway");

            sd.setName("gatekeeper");

            dfd.addServices(sd);

            DFService.register(this, dfd);

        } catch(FIPAException e) {

            e.printStackTrace();
        }

        discoverDecisionNode();

        addBehaviour(new CyclicBehaviour(this) {

            @Override
            public void action() {

                ACLMessage msg = receive();

                if(msg != null) {

                    System.out.println(
                            "Gatekeeper received -> "
                                    + msg.getContent()
                    );

                    // ACK BACK
                    ACLMessage reply =
                            msg.createReply();

                    reply.setPerformative(
                            ACLMessage.CONFIRM
                    );

                    reply.setContent("ACK");

                    send(reply);

                    String raw =
                            msg.getContent();

                    String patientId =
                            "UNKNOWN";

                    int hr = -1;

                    // PARSE
                    try {

                        String[] parts =
                                raw.split(";");

                        for(String part : parts) {

                            if(part.startsWith("patient=")) {

                                patientId =
                                        part.substring(8);
                            }

                            else if(part.startsWith("HR=")) {

                                hr =
                                        Integer.parseInt(
                                                part.substring(3)
                                        );
                            }
                        }

                    } catch(Exception e) {

                        hr = -1;
                    }

                    // FILTER LOGIC
                    String quality;

                    String status;

                    if(hr >= 40 && hr <= 180) {

                        quality = "GOOD";

                        if(hr < 60 || hr > 100) {

                            status = "WARN";
                        }
                        else {

                            status = "NORMAL";
                        }
                    }
                    else {

                        quality = "BAD";

                        status = "DROP";
                    }

                    long ts =
                            System.currentTimeMillis();

                    String filtered =
                            "patient=" + patientId
                                    + ";HR=" + hr
                                    + ";quality=" + quality
                                    + ";status=" + status
                                    + ";ts=" + ts
                                    + ";src=vital";

                    // ALWAYS FORWARD
                    if(decisionAID != null) {

                        ACLMessage forward =
                                new ACLMessage(
                                        ACLMessage.INFORM
                                );

                        forward.addReceiver(
                                decisionAID
                        );

                        forward.setContent(
                                filtered
                        );

                        send(forward);

                        System.out.println(
                                "FORWARDED -> "
                                        + filtered
                        );
                    }
                }
                else {

                    block();
                }
            }
        });
    }

    private void discoverDecisionNode() {

        try {

            DFAgentDescription template =
                    new DFAgentDescription();

            ServiceDescription sd =
                    new ServiceDescription();

            sd.setType("decision-making");

            template.addServices(sd);

            DFAgentDescription[] result =
                    DFService.search(
                            this,
                            template
                    );

            if(result.length > 0) {

                decisionAID =
                        result[0].getName();

                System.out.println(
                        "Found DecisionNode"
                );
            }

        } catch(FIPAException e) {

            e.printStackTrace();
        }
    }
}