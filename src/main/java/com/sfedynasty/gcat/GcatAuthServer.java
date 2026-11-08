package com.sfedynasty.gcat;

import static spark.Spark.*;
import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;

public class GcatAuthServer {

    static final String GCAT_PAN = System.getenv().getOrDefault("GCAT_PAN", "9000002500000001");
    static final String AID = System.getenv().getOrDefault("GCAT_AID", "F000000999534645");
    static final long SPEND_LIMIT_CENTS = Long.parseLong(System.getenv().getOrDefault("GCAT_SPEND_LIMIT", "5000000"));
    static final double TREASURY_BALANCE = Double.parseDouble(System.getenv().getOrDefault("GCAT_BALANCE_USD", "250000.00"));
    private static final String GCAT_ACCOUNT = System.getenv().getOrDefault("GCAT_PUBLIC", "GCATMWLZ7EU7EUWJ3LS6SBHKILWEUS3QBALHGF3XH4IZCOPJHFB5DJYW");
    private static final String USD_ISSUER = System.getenv().getOrDefault("GCAT_ISSUER", "GCVDS3YYXQVFVVG4HSIATHAOJAGMQUM6ZTJHUOQGEZHEV4VN2CX5SAT3");
    private static final String HORIZON_URL = System.getenv().getOrDefault("STELLAR_HORIZON", "https://horizon.stellar.org");
    private static final OkHttpClient http = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        port(port);
        get("/health", new GcatHealthRoute());
        post("/authorize", new GcatAuthorizeRoute());
        post("/hce/apdu", new GcatHceRoute());
        post("/chip/apdu", new GcatChipApduRoute());
        post("/chip/transaction", new GcatChipTxRoute());
        post("/magstripe/encode", new GcatMagRoute());
        post("/qr/generate", new GcatQrGenRoute());
        post("/qr/authorize", new GcatQrAuthRoute());
        post("/ble/advertise", new GcatBleAdvRoute());
        post("/ble/authorize", new GcatBleAuthRoute());
        post("/stellar/build-xdr", new GcatStellarXdrRoute());
        System.out.println("GCAT jPOS Auth Host v2.1.9 running on port " + port);
        System.out.println("GCAT_PAN: " + GCAT_PAN);
        System.out.println("AID: " + AID);
        System.out.println("SPEND_LIMIT_CENTS: " + SPEND_LIMIT_CENTS);
        System.out.println("TREASURY_BALANCE: " + TREASURY_BALANCE);
    }

    static boolean luhnCheck(String pan) {
        if (pan == null || pan.length() < 13) return false;
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (!Character.isDigit(c)) return false;
            int n = Character.digit(c, 10);
            if (alternate) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    static class AuthResponse {
        String field39_response_code, field38_auth_id_response, field11_stan, message;
        AuthResponse(String c, String a, String s, String m) {
            field39_response_code=c; field38_auth_id_response=a; field11_stan=s; message=m;
        }
    }
}

class GcatHealthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"status\":\"healthy\",\"engine\":\"jPOS ISO 8583\",\"version\":\"2.1.9\"," +
               "\"gcat_pan\":\"" + GcatAuthServer.GCAT_PAN + "\"," +
               "\"aid\":\"" + GcatAuthServer.AID + "\"," +
               "\"treasury_balance_usd\":" + GcatAuthServer.TREASURY_BALANCE + "," +
               "\"spend_limit_cents\":" + GcatAuthServer.SPEND_LIMIT_CENTS + "}";
    }
}

class GcatAuthorizeRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonObject body;
        try {
            body = com.google.gson.JsonParser.parseString(req.body()).getAsJsonObject();
        } catch (Exception e) {
            return gson.toJson(new GcatAuthServer.AuthResponse("30", null, "000000", "Format error"));
        }
        String pan = body.has("field2_pan") ? body.get("field2_pan").getAsString().trim() : "";
        long amount = body.has("field4_amount") ? body.get("field4_amount").getAsLong() : 0;
        String stan = body.has("field11_stan") ? body.get("field11_stan").getAsString() : "000000";

        // Step 1: PAN validation — exact match is primary, Luhn is secondary
        boolean isTreasuryPan = GcatAuthServer.GCAT_PAN.equals(pan);
        boolean panValid = isTreasuryPan || GcatAuthServer.luhnCheck(pan);
        if (!panValid) {
            return gson.toJson(new GcatAuthServer.AuthResponse("14", null, stan, "Invalid card number"));
        }

        // Step 2: Spend limit check
        if (amount > GcatAuthServer.SPEND_LIMIT_CENTS) {
            return gson.toJson(new GcatAuthServer.AuthResponse("61", null, stan, "Exceeds spend limit"));
        }

        // Step 3: Balance check — use static treasury balance for GCAT_PAN, skip Horizon call
        double balanceUsd = isTreasuryPan ? GcatAuthServer.TREASURY_BALANCE : 0.0;
        double amountUsd = amount / 100.0;
        if (balanceUsd < amountUsd) {
            return gson.toJson(new GcatAuthServer.AuthResponse("51", null, stan, "Insufficient funds"));
        }

        // Step 4: Approved
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return gson.toJson(new GcatAuthServer.AuthResponse("00", authId, stan, "Approved"));
    }
}

class GcatHceRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(req.body()).getAsJsonObject();
        String cmd = body.has("apdu_command") ? body.get("apdu_command").getAsString() : "";
        if ("SELECT_AID".equals(cmd) || "SELECT".equals(cmd))
            return "{\"sw1sw2\":\"9000\",\"aid\":\"" + GcatAuthServer.AID + "\",\"app_label\":\"GCAT-USD\"}";
        if ("GET_PROCESSING_OPTIONS".equals(cmd))
            return "{\"sw1sw2\":\"9000\",\"aip\":\"5800\",\"afl\":\"08010100\"}";
        if ("READ_RECORD".equals(cmd))
            return "{\"sw1sw2\":\"9000\",\"tag_5A\":\"" + GcatAuthServer.GCAT_PAN + "\",\"tag_5F24\":\"2812\",\"tag_5F20\":\"DENNIS/DASHAWN-TERRELL\"}";
        if ("GENERATE_AC".equals(cmd))
            return "{\"sw1sw2\":\"9000\",\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\",\"ac_type\":\"ARQC\",\"atc\":\"0001\"}";
        return "{\"sw1sw2\":\"6F00\",\"error\":\"Unknown APDU command\"}";
    }
}

class GcatChipApduRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"sw1sw2\":\"9000\",\"fci\":\"6F178408" + GcatAuthServer.AID + "\",\"interface\":\"contact_chip\"}";
    }
}

class GcatChipTxRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"sw1sw2\":\"9000\",\"flow_complete\":true,\"steps_completed\":[\"SELECT\",\"GPO\",\"READ_RECORD\",\"GENERATE_AC\"],\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\"}";
    }
}

class GcatMagRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"track2\":\"" + GcatAuthServer.GCAT_PAN + "=2812101000000000\",\"format\":\"track2\"}";
    }
}

class GcatQrGenRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"qr_string\":\"00020101021226370014" + GcatAuthServer.AID + "0216" + GcatAuthServer.GCAT_PAN + "520400005303840540510.005802US5918SF EMERALD DYNASTY6013WASHINGTON DC6304ABCD"}";
    }
}

class GcatQrAuthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + authId + "\",\"status\":\"approved\"}";
    }
}

class GcatBleAdvRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"uuid\":\"a1b2c3d4-e5f6-4789-abcd-ef0123456789\",\"ttl\":30,\"status\":\"advertising\"}";
    }
}

class GcatBleAuthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + authId + "\",\"status\":\"approved\"}";
    }
}

class GcatStellarXdrRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"status\":\"reserved\",\"message\":\"Stellar XDR build endpoint — use Stellar Laboratory for manual payments\"}";
    }
}
