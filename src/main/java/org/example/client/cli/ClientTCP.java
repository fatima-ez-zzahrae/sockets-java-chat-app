package org.example.client.cli;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.example.dto.Credentials;
import org.example.model.Message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class ClientTCP {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int PORT = 5000;
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    private static Socket socket;
    private static PrintWriter sender;
    private static BufferedReader receiver;
    private static String currentUserEmail;
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);

    public static void main(String[] args) {
        try {
            initializeConnection();
            
            Scanner scanner = new Scanner(System.in);
            if (performLogin(scanner)) {
                startMessageListener();
                handleUserSession(scanner);
            }
        } catch (IOException e) {
            System.err.println("❌ Connection error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static void initializeConnection() throws IOException {
        socket = new Socket(SERVER_ADDRESS, PORT);
        sender = new PrintWriter(socket.getOutputStream(), true);
        receiver = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    private static boolean performLogin(Scanner scanner) throws IOException {
        System.out.println("\n🔐 Login");
        System.out.println("─────────────────");
        System.out.print("Email: ");
        String email = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        if (authenticate(email, password)) {
            System.out.println("\n✅ Authentication successful!");
            currentUserEmail = email;
            return true;
        } else {
            System.out.println("\n❌ Authentication failed!");
            return false;
        }
    }

    private static void startMessageListener() {
        Thread messageListener = new Thread(() -> {
            try {
                String incomingMessage;
                while (isRunning.get() && (incomingMessage = receiver.readLine()) != null) {
                    handleIncomingMessage(incomingMessage);
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    System.out.println("\n❌ Lost connection to server!");
                }
            }
        });
        messageListener.setDaemon(true);
        messageListener.start();
    }

    private static void handleIncomingMessage(String jsonMessage) {
        try {
            Message message = objectMapper.readValue(jsonMessage, Message.class);
            processMessage(message);
        } catch (IOException e) {
            System.err.println("\n❌ Error processing message: " + e.getMessage());
            printPrompt();
        }
    }

    private static void processMessage(Message message) {
        switch (message.getType()) {
            case "CHAT":
                System.out.println("\n📨 Message from " + message.getSenderEmail() + ":");
                System.out.println("   " + message.getContent());
                break;
            case "CONFIRMATION":
                System.out.println(message.getStatus().equals("delivered") ? 
                    "\n✓ Message delivered" : "\n⏳ Message queued (recipient offline)");
                break;
            case "ERROR":
                System.out.println("\n❌ Error: " + message.getContent());
                break;
            case "LOGOUT_CONFIRM":
                isRunning.set(false);
                return;
        }
        printPrompt();
    }

    private static void handleUserSession(Scanner scanner) {
        while (isRunning.get()) {
            printMenu();
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    sendMessage(scanner);
                    break;
                case "2":
                    logout();
                    return;
                default:
                    System.out.println("\n❌ Invalid choice. Please try again.");
            }
        }
    }

    private static void sendMessage(Scanner scanner) {
        try {
            System.out.println("\n✍️ New Message");
            System.out.println("─────────────────");
            System.out.print("To (email): ");
            String receiverEmail = scanner.nextLine();
            
            if (receiverEmail.equalsIgnoreCase("back")) return;

            System.out.print("Message: ");
            String content = scanner.nextLine();
            
            if (content.equalsIgnoreCase("back")) return;

            Message message = new Message(currentUserEmail, receiverEmail, content);
            message.setType("CHAT");
            
            sender.println(objectMapper.writeValueAsString(message));
            System.out.println("\n📤 Sending message...");
            
        } catch (IOException e) {
            System.err.println("\n❌ Error sending message: " + e.getMessage());
        }
    }

    private static boolean authenticate(String email, String password) throws IOException {
        Credentials credentials = new Credentials(email, password);
        sender.println(objectMapper.writeValueAsString(credentials));
        String response = receiver.readLine();
        return "AUTH_SUCCESS".equals(response);
    }

    private static void logout() {
        try {
            Message logoutMsg = new Message(currentUserEmail, null, null);
            logoutMsg.setType("LOGOUT");
            sender.println(objectMapper.writeValueAsString(logoutMsg));
            System.out.println("\n👋 Logging out...");
        } catch (IOException e) {
            System.err.println("\n❌ Error during logout: " + e.getMessage());
        }
    }

    private static void cleanup() {
        try {
            isRunning.set(false);
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    private static void printMenu() {
        System.out.println("\n📱 Chat Menu");
        System.out.println("─────────────────");
        System.out.println("1. Send message");
        System.out.println("2. Logout");
        System.out.print("\nChoice (1-2): ");
    }

    private static void printPrompt() {
        System.out.print("\nChoice (1-2): ");
    }
}
