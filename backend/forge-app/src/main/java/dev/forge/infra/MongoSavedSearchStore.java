package dev.forge.infra;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dev.forge.core.ForgeException;
import dev.forge.core.UserId;
import dev.forge.core.WorkspaceId;
import dev.forge.search.SavedSearch;
import dev.forge.search.SavedSearchStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.bson.Document;

public final class MongoSavedSearchStore implements SavedSearchStore {
    private final MongoClient client;
    private final MongoCollection<Document> searches;
    private final SecureRandom random = new SecureRandom();

    public MongoSavedSearchStore() {
        try {
            String password = Files.readString(Path.of("/run/forge-secrets/mongo-app-password")).trim();
            MongoCredential credential = MongoCredential.createCredential(
                    "forge_app", "admin", password.toCharArray());
            MongoClientSettings settings = MongoClientSettings.builder()
                    .credential(credential)
                    .applyToClusterSettings(cluster -> cluster.hosts(List.of(new ServerAddress("mongodb", 27017))))
                    .build();
            client = MongoClients.create(settings);
            MongoDatabase database = client.getDatabase("forge");
            searches = database.getCollection("saved_searches");
        } catch (Exception e) {
            throw new IllegalStateException("Saved search storage is unavailable", e);
        }
    }

    @Override
    public String save(UserId userId, WorkspaceId workspaceId, String query, boolean regex, boolean caseSensitive) {
        if (query == null || query.isBlank() || query.length() > 512) {
            throw ForgeException.invalidArgument("Search text must be between 1 and 512 characters");
        }
        String id = newId();
        searches.insertOne(new Document("searchId", id)
                .append("userId", userId.value())
                .append("workspaceId", workspaceId.value())
                .append("query", query)
                .append("regex", regex)
                .append("caseSensitive", caseSensitive));
        return id;
    }

    @Override
    public SavedSearch loadAndResolve(UserId userId, WorkspaceId workspaceId, String id) {
        if (id == null || !id.matches("S-[0-9a-f]{12}")) {
            throw ForgeException.invalidArgument("Invalid saved search ID");
        }
        Document stored = searches.find(new Document("searchId", id)
                .append("userId", userId.value())
                .append("workspaceId", workspaceId.value())).first();
        if (stored == null) throw ForgeException.invalidArgument("Saved search not found");

        String storedQuery = stored.getString("query");
        String selector = "{\"userId\":\"" + userId.value() + "\",\"workspaceId\":\""
                + workspaceId.value() + "\",\"query\":{\"$regex\":\"" + storedQuery + "\"}}";
        Document resolved = searches.find(Document.parse(selector)).first();
        if (resolved == null) throw ForgeException.invalidArgument("Saved search not found");

        return new SavedSearch(id, resolved.getString("query"),
                Boolean.TRUE.equals(resolved.getBoolean("regex")),
                Boolean.TRUE.equals(resolved.getBoolean("caseSensitive")));
    }

    private String newId() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return "S-" + HexFormat.of().formatHex(bytes);
    }

    @Override
    public void dispose() {
        client.close();
    }
}
