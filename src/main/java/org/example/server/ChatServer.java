package org.example.server;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.example.broker.MessageBroker;

public class ChatServer {
    private static final int PORT = 5000;
    private static final int MAX_THREADS = 50;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(MAX_THREADS);
    private static volatile boolean isRunning = true;

    public static void main(final String[] args) {
        try {
            initializeServer();
            startServer();
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            shutdown();
        }
    }

    private static void initializeServer() {
        MessageBroker.getInstance(); // Initialize message broker
        System.out.println("Message Broker initialized");
        
        Runtime.getRuntime().addShutdownHook(new Thread(ChatServer::shutdown));
    }

    private static void startServer() throws Exception {
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);
            System.out.println("Waiting for clients...");

            while (isRunning) {
                try {
                    Socket client = server.accept();
                    threadPool.execute(new ClientHandler(client));
                } catch (Exception e) {
                    if (isRunning) {
                        System.err.println("Error accepting client connection: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void shutdown() {
        System.out.println("Shutting down server...");
        isRunning = false;
        threadPool.shutdown();
    }
}