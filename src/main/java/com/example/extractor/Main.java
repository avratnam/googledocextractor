package com.example.extractor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport; // NEW: Import for file writing errors
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;   // NEW: Modern way to handle files
import com.google.api.services.docs.v1.Docs;   // NEW: Used to specify file paths
import com.google.api.services.docs.v1.DocsScopes;
import com.google.api.services.docs.v1.model.Document;

public class Main {

    private static final String APPLICATION_NAME = "Google Docs Extractor";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    private static final List<String> SCOPES = Collections.singletonList(DocsScopes.DOCUMENTS_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    // NEW: Define the output filename as a constant for easy access
    private static final String OUTPUT_FILENAME = "document_output.json";

    private static Credential getCredentials() throws Exception {
        InputStream in = Main.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static void main(String... args) throws Exception {
        // !!! IMPORTANT: PASTE YOUR DOCUMENT ID HERE !!!
        // final String DOCUMENT_ID = "16tPiar8lKkqW4sFKjS1VVbhYgXpJUCfRncLY8XamRVM";
        final String DOCUMENT_ID = "1OsiBbW0KtwKypXgPc8auxvxgYxpWna4VZKSyWJcFnAY";
        
        if (DOCUMENT_ID.equals("PASTE_YOUR_DOCUMENT_ID_HERE")) {
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.err.println("!!! ERROR: Please open Main.java and set the DOCUMENT_ID !!!");
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            return;
        }

        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Docs service = new Docs.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials())
                .setApplicationName(APPLICATION_NAME)
                .build();

        System.out.println("Fetching document...");
        Document document = service.documents().get(DOCUMENT_ID).execute();
        System.out.println("Document fetched: " + document.getTitle());

        // Use our extractor to process the document.
        GoogleDocExtractor extractor = new GoogleDocExtractor();
        String jsonOutput = extractor.extractContentAsJson(document);

        // --- MODIFIED SECTION: Write JSON to a file ---
        try {
            // This single line writes the entire string to the specified file.
            // It automatically handles opening, writing, and closing the file.
            Files.writeString(Paths.get(OUTPUT_FILENAME), jsonOutput);
            
            System.out.println("----------------------------------------------------------------");
            System.out.println("✅ Success! Extracted JSON has been written to the file: " + OUTPUT_FILENAME);
            System.out.println("   You can find this file in the project's root directory.");
            System.out.println("----------------------------------------------------------------");
        } catch (IOException e) {
            System.err.println("❌ Error: Failed to write JSON to file.");
            e.printStackTrace();
        }
    }
}