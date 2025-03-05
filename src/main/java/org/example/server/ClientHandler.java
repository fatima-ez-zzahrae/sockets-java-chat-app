package org.example.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.example.broker.MessageBroker;
import org.example.dto.Credentials;
import org.example.model.Message;
import org.example.service.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private String clientEmail;
    private PrintWriter out;
    private BufferedReader in;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final MessageBroker messageBroker;
    private volatile boolean isRunning = true;

    private static final Map<String, ClientHandler> onlineClients = new ConcurrentHashMap<>();

    public ClientHandler(final Socket socket) {
        this.clientSocket = socket;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.userService = new UserService();
        this.messageBroker = MessageBroker.getInstance();

        try {
            this.out = new PrintWriter(socket.getOutputStream(), true);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (final IOException e) {
            System.err.println("Error initializing client handler: " + e.getMessage());
            throw new RuntimeException("Failed to initialize client handler", e);
        }
    }

    @Override
    public void run() {
        try {
            if (authenticateClient()) {
                handleMessages();
            }
        } catch (final IOException e) {
            System.err.println("Client disconnected during session: " + e.getMessage());
        } finally {
            handleDisconnection();
        }
    }

    private boolean authenticateClient() throws IOException {
        final String jsonCredentials = in.readLine();
        final Credentials credentials = objectMapper.readValue(jsonCredentials, Credentials.class);

        if (userService.authenticateUser(credentials.getEmail(), credentials.getPassword())) {
            setClientEmail(credentials.getEmail());
            out.println("AUTH_SUCCESS");
            registerAsConsumer();
            return true;
        } else {
            out.println("AUTH_FAILED");
            return false;
        }
    }

    private void registerAsConsumer() {
        messageBroker.registerConsumer(clientEmail, message -> {
            try {
                sendMessage(objectMapper.writeValueAsString(message));
                System.out.println("Message sent to " + clientEmail + ": " + message.getId());
            } catch (final IOException e) {
                System.err.println("Error sending message to " + clientEmail + ": " + e.getMessage());
                handleDisconnection();
            }
        });
    }

    private void handleMessages() throws IOException {
        String messageData;
        while (isRunning && (messageData = in.readLine()) != null) {
            try {
                final Message message = objectMapper.readValue(messageData, Message.class);
                processMessage(message);
            } catch (final IOException e) {
                System.err.println("Error processing message: " + e.getMessage());
                sendErrorMessage("Invalid message format");
            }
        }
    }

    private void processMessage(Message message) throws IOException {
        switch (message.getType()) {
            case "CHAT":
                handleChatMessage(message);
                break;
            case "ACKNOWLEDGE":
                messageBroker.acknowledgeMessage(message.getId());
                break;
            case "LOGOUT":
                handleLogout();
                break;
            default:
                sendErrorMessage("Unknown message type: " + message.getType());
        }
    }

    private void handleChatMessage(final Message message) throws IOException {
        final ClientHandler receiverHandler = onlineClients.get(message.getReceiverEmail());
        final boolean delivered = messageBroker.sendMessage(message,
                receiverHandler != null ? this::deliverMessage : null);

        sendDeliveryConfirmation(message, delivered);
    }

    private void deliverMessage(Message message) {
        try {
            final String jsonMessage = objectMapper.writeValueAsString(message);
            final ClientHandler receiver = onlineClients.get(message.getReceiverEmail());
            if (receiver != null) {
                receiver.sendMessage(jsonMessage);
            }
        } catch (final IOException e) {
            System.err.println("Error delivering message: " + e.getMessage());
        }
    }

    private void sendDeliveryConfirmation(Message originalMessage, boolean delivered) throws IOException {
        final Message confirmation = new Message(null, originalMessage.getSenderEmail(), null);
        confirmation.setType("CONFIRMATION");
        confirmation.setStatus(delivered ? "delivered" : "queued");
        confirmation.setId(originalMessage.getId());
        sendMessage(objectMapper.writeValueAsString(confirmation));
    }

    private void handleLogout() {
        try {
            sendMessage("{\"type\":\"LOGOUT_CONFIRM\"}");
        } finally {
            handleDisconnection();
        }
    }

    private void handleDisconnection() {
        if (!isRunning) return;
        
        isRunning = false;
        try {
            if (clientEmail != null) {
                messageBroker.unregisterConsumer(clientEmail);
                userService.setUserOnlineStatus(clientEmail, false);
                onlineClients.remove(clientEmail);
                System.out.println("Client disconnected: " + clientEmail);
            }
            
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (final IOException e) {
            System.err.println("Error during disconnection: " + e.getMessage());
        }
    }

    private void sendErrorMessage(String errorMessage) {
        try {
            Message error = new Message();
            error.setType("ERROR");
            error.setContent(errorMessage);
            sendMessage(objectMapper.writeValueAsString(error));
        } catch (IOException e) {
            System.err.println("Error sending error message: " + e.getMessage());
        }
    }

    public void sendMessage(final String message) {
        if (isRunning && out != null) {
            out.println(message);
        }
    }

    private void setClientEmail(final String email) throws IOException {
        this.clientEmail = email;
        onlineClients.put(email, this);
        userService.setUserOnlineStatus(email, true);
        System.out.println("Client registered: " + email);
    }
}