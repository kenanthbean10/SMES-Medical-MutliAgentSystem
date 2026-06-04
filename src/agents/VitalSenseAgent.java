package agents;

// =====================================================
// IMPORTS
// =====================================================

// JADE core
import jade.core.AID;
import jade.core.Agent;

// JADE behaviours
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;

// JADE messaging
import jade.lang.acl.ACLMessage;

// JADE DF service
import jade.domain.DFService;
import jade.domain.FIPAException;

import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

// Swing GUI
import javax.swing.*;
import java.awt.*;

// =====================================================
// VitalSenseAgent
// =====================================================

public class VitalSenseAgent extends Agent {

    // =================================================
    // GATEKEEPER REFERENCE
    // =================================================

    private AID gatekeeperAID;

    // =================================================
    // SENSOR VARIABLES
    // =================================================

    private volatile int currentHR = 75;

    private String patientId = "PT-001";

    private int samplingPeriod = 2000;

    private TickerBehaviour vitalTicker;

    // =================================================
    // GUI COMPONENTS
    // =================================================

    private JFrame frame;

    private JTextField patientField;
    private JTextField hrField;

    private JLabel statusLabel;
    private JLabel samplingLabel;

    private JButton sendButton;
    private JButton emergencyButton;
    private JButton autoCycleButton;

    // AUTO-CYCLE: when true the ticker alternates between normal (75 bpm)
    // and emergency (160 bpm) every AUTO_CYCLE_TICKS ticks, so you can watch
    // PowerSaver switch rate=LOW <-> rate=HIGH without clicking buttons manually.
    private boolean autoCycleEnabled = false;
    private int autoCycleCounter = 0;
    private static final int AUTO_CYCLE_TICKS = 5; // switch every 5 ticks

    // =================================================
    // SETUP
    // =================================================

    @Override
    protected void setup() {

        System.out.println("VitalSense Agent setup");

        // =================================================
        // REGISTER IN DF
        // =================================================

        try {

            DFAgentDescription dfd =
                    new DFAgentDescription();

            dfd.setName(getAID());

            ServiceDescription sd =
                    new ServiceDescription();

            sd.setType("vital-sensing");

            sd.setName("SMES-VitalSense");

            dfd.addServices(sd);

            DFService.register(this, dfd);

            System.out.println(
                    "VitalSense registered"
            );

        } catch (FIPAException e) {

            e.printStackTrace();
        }

        // =================================================
        // FIND GATEKEEPER
        // =================================================

        discoverGatekeeper();

        // =================================================
        // CREATE GUI
        // =================================================

        SwingUtilities.invokeLater(() -> {

            createGUI();
        });

        // =================================================
        // START SENSOR TICKER
        // =================================================

        startTicker();

        // =================================================
        // LISTEN FOR POWERSAVER
        // =================================================

        addBehaviour(new CyclicBehaviour(this) {

            @Override
            public void action() {

                ACLMessage msg = receive();

                if (msg != null) {

                    System.out.println(
                            "Vital received: "
                                    + msg.getContent()
                    );

                    // =========================================
                    // HANDLE RATE CHANGE
                    // =========================================

                    if (
                            msg.getPerformative()
                                    == ACLMessage.REQUEST

                                    &&

                                    msg.getContent() != null

                                    &&

                                    msg.getContent()
                                            .startsWith("rate=")
                    ) {

                        String rate =
                                msg.getContent()
                                        .substring(5);

                        int newPeriod;

                        // Emergency mode — sample every 1 second
                        if ("HIGH".equals(rate)) {

                            newPeriod = 1000;
                        }

                        // Sensor failure recovery mode — sample every 2 seconds.
                        // Faster than normal to detect when the sensor recovers,
                        // but not full speed since this is not a clinical emergency.
                        else if ("MEDIUM".equals(rate)) {

                            newPeriod = 2000;
                        }

                        // Battery save mode — sample every 4 seconds
                        else {

                            newPeriod = 4000;
                        }

                        // =====================================
                        // RESTART TICKER
                        // =====================================

                        if (newPeriod != samplingPeriod) {

                            samplingPeriod = newPeriod;

                            removeBehaviour(vitalTicker);

                            startTicker();
                        }

                        // =====================================
                        // UPDATE GUI
                        // =====================================

                        samplingLabel.setText(
                                "Sampling Rate: "
                                        + samplingPeriod
                                        + " ms"
                        );

                        statusLabel.setText(
                                "PowerSaver changed rate"
                        );

                        // =====================================
                        // SEND AGREE
                        // =====================================

                        ACLMessage reply =
                                msg.createReply();

                        reply.setPerformative(
                                ACLMessage.AGREE
                        );

                        reply.setContent(
                                "done period="
                                        + samplingPeriod
                        );

                        send(reply);
                    }
                }

                else {

                    block();
                }
            }
        });
    }

    // =================================================
    // GUI
    // =================================================

    private void createGUI() {

        frame = new JFrame(
                "VitalSense Healthcare Monitor"
        );

        frame.setSize(550, 420);

        frame.setLocationRelativeTo(null);

        frame.setDefaultCloseOperation(
                JFrame.EXIT_ON_CLOSE
        );

        // =================================================
        // MAIN PANEL
        // =================================================

        JPanel panel = new JPanel();

        panel.setLayout(
                new GridLayout(9, 1, 10, 10)
        );

        panel.setBorder(
                BorderFactory.createEmptyBorder(
                        20,
                        20,
                        20,
                        20
                )
        );

        // =================================================
        // TITLE
        // =================================================

        JLabel title =
                new JLabel(
                        "VitalSense Medical Device"
                );

        title.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        24
                )
        );

        title.setHorizontalAlignment(
                SwingConstants.CENTER
        );

        panel.add(title);

        // =================================================
        // PATIENT ID
        // =================================================

        JPanel patientPanel =
                new JPanel(
                        new BorderLayout()
                );

        patientPanel.add(
                new JLabel("Patient ID: "),
                BorderLayout.WEST
        );

        patientField =
                new JTextField("PT-001");

        patientPanel.add(
                patientField,
                BorderLayout.CENTER
        );

        panel.add(patientPanel);

        // =================================================
        // HEART RATE
        // =================================================

        JPanel hrPanel =
                new JPanel(
                        new BorderLayout()
                );

        hrPanel.add(
                new JLabel("Heart Rate: "),
                BorderLayout.WEST
        );

        hrField =
                new JTextField("75");

        hrPanel.add(
                hrField,
                BorderLayout.CENTER
        );

        panel.add(hrPanel);

        // =================================================
        // STATUS
        // =================================================

        statusLabel =
                new JLabel("Status: READY");

        statusLabel.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        16
                )
        );

        panel.add(statusLabel);

        // =================================================
        // SAMPLING LABEL
        // =================================================

        samplingLabel =
                new JLabel(
                        "Sampling Rate: "
                                + samplingPeriod
                                + " ms"
                );

        samplingLabel.setFont(
                new Font(
                        "Arial",
                        Font.PLAIN,
                        15
                )
        );

        panel.add(samplingLabel);

        // =================================================
        // SEND BUTTON
        // =================================================

        sendButton =
                new JButton("Send Heart Rate");

        sendButton.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        16
                )
        );

        sendButton.addActionListener(e -> {

            sendVitalData();
        });

        panel.add(sendButton);

        // =================================================
        // EMERGENCY BUTTON
        // =================================================

        emergencyButton =
                new JButton(
                        "Simulate Emergency"
                );

        emergencyButton.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        16
                )
        );

        emergencyButton.addActionListener(e -> {

            // FIX: was 145 — DecisionNode threshold is HR > 150 for EMERGENCY.
            // 145 only triggered WARN, so PowerSaver never received state=EMERGENCY.
            // 160 is safely above the threshold and triggers the full EMERGENCY path.
            hrField.setText("160");

            sendVitalData();
        });

        panel.add(emergencyButton);

        // =================================================
        // AUTO-CYCLE BUTTON
        // Toggles automatic alternation between HR=75 (NORMAL)
        // and HR=160 (EMERGENCY) so PowerSaver rate changes are
        // visible without manual button clicks during a demo.
        // =================================================

        autoCycleButton = new JButton("Auto-Cycle OFF");
        autoCycleButton.setFont(new Font("Arial", Font.BOLD, 16));
        autoCycleButton.setBackground(Color.LIGHT_GRAY);

        autoCycleButton.addActionListener(e -> {
            autoCycleEnabled = !autoCycleEnabled;
            autoCycleCounter = 0; // reset counter on toggle
            if (autoCycleEnabled) {
                autoCycleButton.setText("Auto-Cycle ON  (click to stop)");
                autoCycleButton.setBackground(Color.GREEN);
                statusLabel.setText("Auto-cycling NORMAL <-> EMERGENCY");
            } else {
                autoCycleButton.setText("Auto-Cycle OFF");
                autoCycleButton.setBackground(Color.LIGHT_GRAY);
                hrField.setText("75"); // return to normal when stopped
                statusLabel.setText("Auto-cycle stopped");
            }
        });

        panel.add(autoCycleButton);

        // =================================================
        // SHOW GUI
        // =================================================

        frame.add(panel);

        frame.setVisible(true);
    }

    // =================================================
    // SEND DATA MANUALLY
    // =================================================

    private void sendVitalData() {

        try {

            // =============================================
            // READ GUI VALUES
            // =============================================

            patientId =
                    patientField.getText().trim();

            String hrText =
                    hrField.getText().trim();

            // Empty validation
            if (patientId.isEmpty()) {

                statusLabel.setText(
                        "Patient ID required"
                );

                return;
            }

            if (hrText.isEmpty()) {

                statusLabel.setText(
                        "Heart Rate required"
                );

                return;
            }

            currentHR =
                    Integer.parseInt(hrText);

            // =============================================
            // CREATE MESSAGE
            // =============================================

            ACLMessage msg =
                    new ACLMessage(
                            ACLMessage.INFORM
                    );

            if (gatekeeperAID != null) {

                msg.addReceiver(gatekeeperAID);

                msg.setContent(
                        "patient="
                                + patientId
                                + ";HR="
                                + currentHR
                );

                send(msg);

                statusLabel.setText(
                        "Data sent successfully"
                );

                System.out.println(
                        "MANUAL SEND -> "
                                + msg.getContent()
                );
            }

            else {

                statusLabel.setText(
                        "No Gatekeeper found"
                );
            }

        } catch (Exception e) {

            statusLabel.setText(
                    "Invalid HR value"
            );
        }
    }

    // =================================================
    // AUTO SENSOR TICKER
    // =================================================

    private void startTicker() {

        vitalTicker =
                new TickerBehaviour(
                        this,
                        samplingPeriod
                ) {

                    @Override
                    protected void onTick() {

                        try {

                            // =====================================
                            // AUTO-CYCLE LOGIC
                            // When enabled, automatically alternates
                            // the HR field between 75 (NORMAL) and
                            // 160 (EMERGENCY) every AUTO_CYCLE_TICKS
                            // ticks, so PowerSaver rate transitions
                            // are visible without manual intervention.
                            // =====================================

                            if (autoCycleEnabled) {
                                autoCycleCounter++;
                                if (autoCycleCounter % (AUTO_CYCLE_TICKS * 2) < AUTO_CYCLE_TICKS) {
                                    // first half of cycle = NORMAL
                                    hrField.setText("75");
                                } else {
                                    // second half of cycle = EMERGENCY (above 150 threshold)
                                    hrField.setText("160");
                                }
                            }

                            // =====================================
                            // IMPORTANT FIX
                            // =====================================
                            //
                            // Read GUI EVERY TICK
                            //
                            // So user changes are live
                            //
                            // =====================================

                            patientId =
                                    patientField
                                            .getText()
                                            .trim();

                            currentHR =
                                    Integer.parseInt(
                                            hrField
                                                    .getText()
                                                    .trim()
                                    );

                            if (gatekeeperAID == null) {

                                System.out.println(
                                        "No Gatekeeper"
                                );

                                return;
                            }

                            ACLMessage msg =
                                    new ACLMessage(
                                            ACLMessage.INFORM
                                    );

                            msg.addReceiver(
                                    gatekeeperAID
                            );

                            msg.setContent(
                                    "patient="
                                            + patientId
                                            + ";HR="
                                            + currentHR
                            );

                            send(msg);

                            System.out.println(
                                    "AUTO SEND -> "
                                            + msg.getContent()
                                            + " period="
                                            + samplingPeriod
                            );

                        } catch (Exception e) {

                            statusLabel.setText(
                                    "Invalid auto-send data"
                            );
                        }
                    }
                };

        addBehaviour(vitalTicker);
    }

    // =================================================
    // FIND GATEKEEPER
    // =================================================

    private void discoverGatekeeper() {

        try {

            DFAgentDescription template =
                    new DFAgentDescription();

            ServiceDescription sd =
                    new ServiceDescription();

            sd.setType("gateway");

            template.addServices(sd);

            DFAgentDescription[] result =
                    DFService.search(
                            this,
                            template
                    );

            if (result.length > 0) {

                gatekeeperAID =
                        result[0].getName();

                System.out.println(
                        "Found Gatekeeper: "
                                + gatekeeperAID
                                .getLocalName()
                );
            }

            else {

                System.out.println(
                        "No Gatekeeper found"
                );
            }

        } catch (FIPAException e) {

            e.printStackTrace();
        }
    }

    // =================================================
    // TAKE DOWN
    // =================================================

    @Override
    protected void takeDown() {

        try {

            DFService.deregister(this);

        } catch (FIPAException e) {

            e.printStackTrace();
        }

        System.out.println(
                "VitalSense deregistered"
        );
    }
}