package ir.muffinsmp.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MuffinBridge extends JavaPlugin {

    private HttpClient httpClient;

    private String apiUrl;
    private String secret;

    private int pollSeconds;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    @Override
    public void onEnable() {

        saveDefaultConfig();

        apiUrl = getConfig().getString(
                "api-url",
                "https://api.muffinsmp.ir"
        );

        secret = getConfig().getString(
                "secret",
                "CHANGE_THIS_SECRET"
        );

        pollSeconds = getConfig().getInt(
                "poll-seconds",
                5
        );

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        getLogger().info("--------------------------------");
        getLogger().info("MuffinBridge enabled");
        getLogger().info("API: " + apiUrl);
        getLogger().info("Poll interval: " + pollSeconds + " seconds");
        getLogger().info("--------------------------------");

        startDeliveryTask();
    }

    @Override
    public void onDisable() {

        getLogger().info("MuffinBridge disabled.");
    }

    private void startDeliveryTask() {

        long delay = pollSeconds * 20L;

        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::checkDeliveries,
                40L,
                delay
        );
    }

    private void checkDeliveries() {

        if (!processing.compareAndSet(false, true)) {
            return;
        }

        try {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/delivery/pending"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + secret)
                    .header("User-Agent", "MuffinBridge/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {

                getLogger().warning(
                        "Delivery API returned HTTP "
                                + response.statusCode()
                );

                return;
            }

            String json = response.body();

            if (json == null || json.trim().isEmpty()) {
                return;
            }

            if (json.contains("\"id\":null")
                    || json.equals("{}")
                    || json.equals("[]")
                    || json.contains("\"pending\":false")) {

                return;
            }

            Delivery delivery = parseDelivery(json);

            if (delivery == null) {

                getLogger().warning(
                        "Could not parse delivery response: "
                                + json
                );

                return;
            }

            getLogger().info(
                    "New delivery: "
                            + delivery.type
                            + " / "
                            + delivery.item
                            + " -> "
                            + delivery.player
            );

            executeDelivery(delivery);

        } catch (Exception e) {

            getLogger().warning(
                    "Delivery check failed: "
                            + e.getMessage()
            );

        } finally {

            processing.set(false);
        }
    }

    private void executeDelivery(Delivery delivery) {

        Bukkit.getScheduler().runTask(
                this,
                () -> {

                    boolean success;

                    String command =
                            buildCommand(delivery);

                    if (command == null) {

                        getLogger().warning(
                                "Unknown purchase: "
                                        + delivery.type
                                        + " / "
                                        + delivery.item
                        );

                        markFailed(
                                delivery.id,
                                "Unknown purchase type/item"
                        );

                        return;
                    }

                    getLogger().info(
                            "Executing: "
                                    + command
                    );

                    try {

                        success =
                                Bukkit.dispatchCommand(
                                        Bukkit.getConsoleSender(),
                                        command
                                );

                    } catch (Exception e) {

                        success = false;

                        getLogger().warning(
                                "Command execution error: "
                                        + e.getMessage()
                        );
                    }

                    if (success) {

                        getLogger().info(
                                "Delivery command executed successfully."
                        );

                        markComplete(delivery.id);

                    } else {

                        getLogger().warning(
                                "Delivery command returned false."
                        );

                        markFailed(
                                delivery.id,
                                "Minecraft command failed"
                        );
                    }
                }
        );
    }

    private String buildCommand(Delivery delivery) {

        String player = delivery.player;

        if (player == null || player.isBlank()) {
            return null;
        }

        if (delivery.type.equalsIgnoreCase("rank")) {

            switch (delivery.item.toUpperCase()) {

                case "NOVA":
                    return "nova " + player;

                case "VANTA":
                    return "vanta " + player;

                case "APEX":
                    return "apex " + player;

                case "SPONSOR":
                    return "sponsor " + player;

                default:
                    return null;
            }
        }

        if (delivery.type.equalsIgnoreCase("key")) {

            switch (delivery.item.toLowerCase()) {

                case "prime":
                    return "dc givekey "
                            + player
                            + " prime 1";

                case "gold":
                    return "dc givekey "
                            + player
                            + " gold 1";

                case "crimson":
                    return "dc givekey "
                            + player
                            + " crimson 1";

                case "amethyst":
                    return "dc givekey "
                            + player
                            + " amethyst 1";

                case "common":
                    return "dc givekey "
                            + player
                            + " common 1";

                default:
                    return null;
            }
        }

        return null;
    }

    private void markComplete(String id) {

        sendStatusRequest(
                "/api/delivery/complete",
                id,
                "delivered"
        );
    }

    private void markFailed(
            String id,
            String reason
    ) {

        sendStatusRequest(
                "/api/delivery/failed",
                id,
                "failed"
        );
    }

    private void sendStatusRequest(
            String endpoint,
            String id,
            String status
    ) {

        Bukkit.getScheduler().runTaskAsynchronously(
                this,
                () -> {

                    try {

                        String body =
                                "{"
                                        + "\"id\":\""
                                        + escape(id)
                                        + "\","
                                        + "\"status\":\""
                                        + escape(status)
                                        + "\""
                                        + "}";

                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        apiUrl
                                                                + endpoint
                                                )
                                        )
                                        .timeout(
                                                Duration.ofSeconds(15)
                                        )
                                        .header(
                                                "Authorization",
                                                "Bearer " + secret
                                        )
                                        .header(
                                                "Content-Type",
                                                "application/json"
                                        )
                                        .header(
                                                "User-Agent",
                                                "MuffinBridge/1.0"
                                        )
                                        .POST(
                                                HttpRequest.BodyPublishers
                                                        .ofString(body)
                                        )
                                        .build();

                        HttpResponse<String> response =
                                httpClient.send(
                                        request,
                                        HttpResponse.BodyHandlers
                                                .ofString()
                                );

                        if (response.statusCode() < 200
                                || response.statusCode() >= 300) {

                            getLogger().warning(
                                    "Could not update delivery "
                                            + id
                                            + ". HTTP "
                                            + response.statusCode()
                            );

                        } else {

                            getLogger().info(
                                    "Delivery "
                                            + id
                                            + " marked as "
                                            + status
                            );
                        }

                    } catch (Exception e) {

                        getLogger().warning(
                                "Status update failed: "
                                        + e.getMessage()
                        );
                    }
                }
        );
    }

    private Delivery parseDelivery(String json) {

        String id =
                getJsonValue(
                        json,
                        "id"
                );

        String type =
                getJsonValue(
                        json,
                        "type"
                );

        String item =
                getJsonValue(
                        json,
                        "item"
                );

        String player =
                getJsonValue(
                        json,
                        "minecraft_username"
                );

        if (player == null) {

            player =
                    getJsonValue(
                            json,
                            "player"
                    );
        }

        if (id == null
                || type == null
                || item == null
                || player == null) {

            return null;
        }

        return new Delivery(
                id,
                type,
                item,
                player
        );
    }

    private String getJsonValue(
            String json,
            String key
    ) {

        Pattern pattern =
                Pattern.compile(
                        "\""
                                + Pattern.quote(key)
                                + "\"\\s*:\\s*\"([^\"]*)\"",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher matcher =
                pattern.matcher(json);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private String escape(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static class Delivery {

        private final String id;
        private final String type;
        private final String item;
        private final String player;

        private Delivery(
                String id,
                String type,
                String item,
                String player
        ) {

            this.id = id;
            this.type = type;
            this.item = item;
            this.player = player;
        }
    }
}
