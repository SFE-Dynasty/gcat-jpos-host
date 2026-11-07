package com.sfedynasty.gcat;

import static spark.Spark.*;
import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;

public class GcatAuthServer {

    private static final String GCAT_PAN = System.getenv().getOrDefault("GCAT_PAN", "4815200000250000");
    private static final long SPEND_LIMIT_CENTS = Long.parseLong(System.getenv().getOrDefault("SPEND_LIMIT_CENTS", "5000000"));
    private static final String GCAT_ACCOUNT = System.getenv().getOrDefault("GCAT_ACCOUNT", "GCATMWLZ7EU7EUWH“S6SSBHKILWEUS3QBALHGF3XH4IZCOPJHFB5DJQW");
    private static final String USD_ISSUER = System.getenv().getOrDefault("USD_ISSUER", "GCVDS3YYXQVFVVG4HSIBZAHQJAGMQUM6ZTJHUOQGEZHEV4VN2CX5SAT3");
    private static final String HORIZON_URL = System.getenv().getOrDefault("HORIZON_URL", "https://horizon.stellar.org");
    private static final OkHttpClient http = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        port(port);

        get("/health", (req, res) -> {
            res.type("application/json");
            return gson.toJson(new HealthResponse("healthy", "jPOS ISO 8583", "2.1.9", GCAT_PAN));
        });

        post("/authorize", (req, res) -> {
            res.type("application/json");
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            String pan = body.has("field2_pan") ? body.get("field2_pan").getAsString() : "";
            long amount = body.has("field4_amount") ? body.get("field4_amount").getAsLong() : 0;
            String stan = body.has("field11_stan") ? body.get("field11_stan").getAsString() : "000000";

            if (!GCAT_PAN.equals(pan)) return gson.toJson(new AuthResponse("14", null, stan, "Invalid card number"));
            if (amount > SPEND_LIMIT_CENTS) return gson.toJson(new AuthResponse("61", null, stan, "Exceeds spend limit"));

            double balance = getHorizonBalance();
            if (balance < 0) return gson.toJson(new AuthResponse("91", null, stan, "Issuer unavailable"));
            if (balance < amount / 100.0) return gson.toJson(new AuthResponse("51", null, stan, "Insufficient funds"));

            String authId = String.format("%06d", (int)(Math.random() * 999999));
            return gson.toJson(new AuthResponse("00", authId, stan, "Approved"));
        });

        post("/hce/apdu", (req, res) -> {
            res.type("application/json");
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            String cmd = body.has("apdu_command") ? body.get("apdu_command").getAsString() : "";
            return switch (cmd) {
                case "SELECT" -> "{\"sw1sw2\":\"9000\",\"aid\":\"A000000999534645\",\"app_label\":\"GCAT-USD\"}";
                case "GET_PROCESSING_OPTIONS" -> "{\"sw1sw2\":\"9000\",\"aip\":\"5800\",\"afl\":\"08010100\"}";
                case "READ_RECORD" -> "{\"sw1sw2\":\"9000\",\"tag_5A\":\"4815200000250000\",\"tag_5F24\":\"2812\",\"tag_5F20\":\"DENNIS/DASHAWN-TERRELL\"}";
                case "GENERATE_AC" -> "{\"sw1sw2\":\"9000\",\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\",\"ac_type\":\"ARQC\",\"atc\":\"0001\"}";
                default -> "{\"sw1sw2\":\"6F00\",\"error\":\"Unknown APDU command\"}";
            };
        });

        post("/chip/apdu", (req, res) -> { res.type("application/json"); return "{\"sw1sw2\":\"9000\",\"fci\":\"6F178408A000000999534645\",\"interface\":\"contact_chip\"}"; });
        post("/chip/transaction", (req, res) -> { res.type("application/json"); return "{\"sw1sw2\":\"9000\",\"flow_complete\":true,\"steps_completed\":[\"SELECT\",\"GPO\",\"READ_RECORD\",\"GENERATE_AC\"],\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\"}"; });
        post("/magstripe/encode", (req, res) -> { res.type("application/json"); return "{\"track2\":\"4815200000250000=2812101000000000\",\"format\":\"track2\"}"; });
        post("/qr/generate", (req, res) -> { res.type("application/json"); return "{\"qr_string\":\"00020101021226370014A00000099953464502164815200000250000520400005303840540510.005802US5918SF EMERALD DYNASTY6013WASHINGTON DC6304ABCD]"}"; });
        post("/qr/authorize", (req, res) -> { res.type("application/json"); return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + String.format("%06d", (int)(Math.random()*999999)) + "\",\"status\":\"approved\"}"; });
        post("/ble/advertise", (req, res) -> { res.type("application/json"); return "{\"uuid\":\"a1b2c3d4-e5f6-4789-abcd-ef0123456789\",\"ttl\":30,\"status\":\"advertising\"}"; });
        post("/ble/authorize", (req, res) -> { res.type("application/json"); return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + String.format("%06d", (int)(Math.random()*999999)) + "\",\"status\":\"approved\"}"; });

        System.out.println("GCAT jPOS Auth Host running on port " + port);
    }

    private static double getHorizonBalance() {
        try {
            Request req = new Request.Builder().url(HORIZON_URL + "/accounts/" + GCAT_ACCOUNT).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return -1;
                JsonObject body = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                JsonArray balances = body.getAsJsonArray("balances");
                for (JsonElement el : balances) {
                    JsonObject b = el.getAsJsonObject();
                    if (b.has("asset_code") && "USD".equals(b.get("asset_code").getAsString())
                        && b.has("asset_issuer") && b.get("asset_issuer").getAsString().startsWith("GCVDS3")) {
                        return Double.parseDouble(b.get("balance").getAsString());
                    }
                }
            }
        } catch (IOException e) { return -1; }
        return -1;
    }

    record HealthResponse(String status, String engine, String version, String gcat_pan) {}
    record AuthResponse(String field39_response_code, String field38_auth_id_response, String field11_stan, String message) {}
}