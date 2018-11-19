import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class Subordinate {

    private Socket coordinatorSocket;
    private BufferedReader reader;
    private OutputStreamWriter writer;
    private Logger logger;


    private Subordinate(Socket socket, String index) throws IOException {

        this.coordinatorSocket = socket;
        this.reader = new BufferedReader(new InputStreamReader(coordinatorSocket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new OutputStreamWriter(coordinatorSocket.getOutputStream(), StandardCharsets.UTF_8);
        String filename = ("/tmp/Subordinate").concat(String.valueOf(index).concat("Log.txt"));
        this.logger = new Logger(filename, "Subordinate", true);
    }

    private Socket getCoordinatorSocket() {
        return coordinatorSocket;
    }

    private String receive(boolean verbosely) throws IOException {

        String msg = this.reader.readLine();

        if (verbosely && !msg.equals("")){
            System.out.println("C: \"" + msg + "\"\n");
        }

        return msg;
    }

    private void send(String msg) throws IOException {

        Printer.print("", "");
        this.writer.write(msg + "\n");
        switch (msg) {
            case ("Y"): {
                System.out.println("Message sent to coordinator: \"YES\"");
                break;
            }
            case ("N"): {
                System.out.println("Message sent to coordinator: \"NO\"");
                break;
            }
            case ("") : {
                System.out.println("[No message sent to coordinator]");
                break;
            }
            default: {
                System.out.println("Message sent to coordinator: \"" + msg + "\"");
                break;
            }
        }
        this.writer.flush();

    }

    private void initiate() throws IOException {

        System.out.println("\nMy coordinator (C) is @ port " + this.getCoordinatorSocket().getPort() + "\n\n");

        String loggedDecision = this.logger.readLog().split(" ")[0];

        if(loggedDecision.equals("ABORT") || loggedDecision.equals("PREPARED") || loggedDecision.equals("COMMIT")) {

            //TODO: Handle the case, in which the subordinate crashed after force-writing PREPARE and before receiving the decision.
            this.resurrect(loggedDecision);

        } else {

            this.phaseOne();

        }

    }

    private void phaseOne() throws IOException {

        Printer.print("=============== START OF PHASE 1 ===============", "blue");


        boolean messageArrived = false;
        String prepareMsg = "";

        try {

            prepareMsg = this.receive(true);
            messageArrived = true;

        } catch (NullPointerException ste) {

            Printer.print("C: [No \"PREPARE\"-message received from coordinator", "white]");
            Printer.print("\n=============== SUBORDINATE CRASHES =================\n", "red");

        }

        // Here, for all further incoming messages, a timeout (defined in Coordinator.java) is set.
        this.coordinatorSocket.setSoTimeout(Coordinator.TIMEOUT_MILLISECS);

        if (messageArrived && prepareMsg.equals("PREPARE")){

            System.out.print("Please enter the vote ('y' for 'YES'/ 'n' for 'NO') to be sent back to the coordinator within "
                    + Coordinator.TIMEOUT_MILLISECS/1000 + " seconds. ");
            System.out.print("If you wish to let this subordinate fail at this stage, please enter 'f': ");
            long startTime = System.currentTimeMillis();
            long timeDiff = 0;
            boolean userInputPresent = false;

            InputHandler inputHandler = new InputHandler(new Scanner(System.in));
            inputHandler.start();

            while(!userInputPresent && (timeDiff < Coordinator.TIMEOUT_MILLISECS)) {

                userInputPresent = inputHandler.isInputYetReceived();
                timeDiff = System.currentTimeMillis() - startTime;

                System.out.print("");

            }

            if (userInputPresent &&
                    inputHandler.getUserInput().toUpperCase().equals("Y") &&
                    ((System.currentTimeMillis() - startTime) < Coordinator.TIMEOUT_MILLISECS))  {

                this.logger.log("PREPARED", true, true, true);
                this.send("Y");
                Printer.print("=============== END OF PHASE 1 =================\n", "blue");
                this.phaseTwo();

            } else if (userInputPresent &&
                    inputHandler.getUserInput().toUpperCase().equals("N") &&
                    ((System.currentTimeMillis() - startTime) < Coordinator.TIMEOUT_MILLISECS)) {

                this.logger.log("ABORT", true, true, true);
                this.send("N");
                Printer.print("=============== END OF PHASE 1 =================\n", "blue");
                this.phaseTwo();


            } else if (userInputPresent &&
                    inputHandler.getUserInput().toUpperCase().equals("F") &&
                    ((System.currentTimeMillis() - startTime) < Coordinator.TIMEOUT_MILLISECS)){

                Printer.print("=============== SUBORDINATE CRASHES =================\n", "red");

                // Terminate the program, even if System.in still blocks in InputHandler
                System.exit(0);

            } else {

                Printer.print("\nNo valid input detected within " + Coordinator.TIMEOUT_MILLISECS / 1000 + " seconds!", "red");
                Printer.print("=============== SUBORDINATE CRASHES =================\n", "red");

                // Terminate the program, even if System.in still blocks in InputHandler
                System.exit(0);

            }

        }

    }

    private void phaseTwo() throws IOException {

        Printer.print("\n=============== START OF PHASE 2 ===============", "green");
        Printer.print("Waiting for the coordinator's decision message...", "white");

        String decisionMsg = "";
        String loggedVote = this.logger.readLog().split(" ")[0];
        int attempts = 0;
        boolean msgArrived = false;
        long startTime = 0;
        boolean recoveryProcessStarted = false;

        while(attempts < 3 && !msgArrived) {

            try {

                if(!(loggedVote.equals("PREPARED") || loggedVote.equals("ABORT"))) {

                    throw new IOException("Illegal logged vote read: "+ loggedVote);

                } else if (attempts > 0) {

                    if(loggedVote.equals("PREPARED")) {

                        this.send("Y");

                    } else {

                        this.send("N");

                    }

                }
                startTime = System.currentTimeMillis();
                decisionMsg = this.receive(true);
                msgArrived = true;

            } catch (SocketTimeoutException ste) {

                Printer.print("\nNo message received from coordinator", "white");

                if (!recoveryProcessStarted) {

                    Printer.print("\n=============== START OF RECOVERY PROCESS ===============", "orange");
                    recoveryProcessStarted = true;

                }

                int printAttempt = attempts + 2;
                if(attempts < 2) Printer.print("\nStarting attempt " + printAttempt + "...", "white");

                ++attempts;

            } catch (NullPointerException | SocketException e) {

                while((System.currentTimeMillis() - startTime) < Coordinator.TIMEOUT_MILLISECS) {

                    // wait

                }

                Printer.print("\nNo message received from coordinator!", "orange");

                if (!recoveryProcessStarted) {

                    Printer.print("\n=============== START OF RECOVERY PROCESS ===============", "orange");
                    recoveryProcessStarted = true;

                }

                int printAttempt = attempts + 2;
                if(attempts < 2) Printer.print("\nStarting attempt " + printAttempt + "...", "white");

                // Resetting the clock
                startTime = System.currentTimeMillis();
                ++attempts;

            } catch (IOException ioe) {

                ioe.printStackTrace();

            }
        }

        if(attempts >= 3) {

            Printer.print("\nCoordinator is considered crashed permanently!", "red");

            if(!this.logger.readLog().split(" ")[0].equals("ABORT")){

                this.logger.log("ABORT", true, true, true);

            }

            this.logger.log("END", false, true, true);

            Printer.print("=============== END OF RECOVERY PROCESS =================", "orange");
            Printer.print("=============== UNILATERAL ABORT =================\n", "red");

        } else {

            switch (decisionMsg) {

                case "COMMIT":
                case "ABORT":

                    if (!this.logger.readLog().split(" ")[0].equals("ABORT") &&
                        !this.logger.readLog().split(" ")[0].equals("COMMIT")) {

                        this.logger.log(decisionMsg, true, true, true);

                    }

                    this.sendAck();

                    break;

                default:

                    throw new IOException("Illegal decision message received from coordinator: " + decisionMsg);

            }
        }

    }

    private void sendAck() throws IOException {

        System.out.print("Please press enter within " + Coordinator.TIMEOUT_MILLISECS / 1000 + " seconds, for" +
                " letting this subordinate acknowledge the coordinator's decision: ");

        InputHandler inputHandler = new InputHandler(new Scanner(System.in));
        inputHandler.start();
        boolean inputPresent = false;

        long startTime = System.currentTimeMillis();
        long timeDiff = 0;

        while (!inputPresent && (timeDiff < Coordinator.TIMEOUT_MILLISECS)) {

            inputPresent = inputHandler.isInputYetReceived();
            timeDiff = System.currentTimeMillis() - startTime;

            System.out.print("");

        }

        if (inputPresent &&
                inputHandler.getUserInput().toUpperCase().equals("") &&
                (timeDiff < Coordinator.TIMEOUT_MILLISECS)) {

            this.send("ACK");
            this.logger.log("END", false, true, true);
            Printer.print("=============== END OF PHASE 2 =================\n", "green");


        } else {

            Printer.print("\nNot acknowledged within " + Coordinator.TIMEOUT_MILLISECS / 1000 + " seconds!", "red");
            Printer.print("=============== SUBORDINATE CRASHES =================\n", "red");

            // Terminate the program, even if System.in still blocks in InputHandler
            System.exit(0);
        }

    }

    private void resurrect(String loggedDecision) throws IOException {

        Printer.print("=============== SUBORDINATE RESURRECTS =================\n", "red");
        Printer.print("Coordinator-log reads: \"" + loggedDecision + "\"", "white");
        Printer.print("Re-entering phase 2...\n", "green");

        this.phaseTwo();
    }

    private static void printHelp(){

        System.out.println("USAGE\n=====\n arguments:\n  - Subordinate -F x       // x (integer) defines this " +
                "Subordinate's index required for its log's filename");

    }

    public static void main(String[] args) throws IOException {

        if ((args.length == 2) && (args[0].equals("-F")) && (Integer.parseInt(args[1]) > 0)) {

            Socket coordinatorSocket = new Socket("localhost", 8080);

            try {

                Subordinate subordinate = new Subordinate(coordinatorSocket, args[1]);
                subordinate.initiate();

            } finally {

                coordinatorSocket.close();

            }

        } else {

            printHelp();

        }
    }
}
