# GCAT Treasury Card — jPOS Authorization Host

ISO 8583 authorization host for the GCAT DAO Treasury closed-loop card system.

## SF Emerald Dynasty \u00b7 US-SFE-PFIA-2025-T1A01

### Endpoints
- GET /health
- POST /authorize
- POST /hce/apdu
- POST /chip/apdu
- POST /chip/transaction
- POST /magstripe/encode
- POST /qr/generate
- POST /qr/authorize
- POST /ble/advertise
- POST /ble/authorize

### Deploy to Render
Build: `mvn clean package -DskipTests`
Start: `java -jar target/gcat-jpos-host-1.0.0.jar`

### Environment Variables
- GCAT_PAN=9000002500000001
- GCAT_ACCOUNT=GCATMWLZ7EU7EUWJ3LS6SBHKILWEUS3QBALHGF3XH4IZCOPJHFB5DJYW
- USD_ISSUER=GCVDS3YYXQVFVVG4HSIBZAHQJAGMQUM6ZTJHUOQGEZHEV4VN2CX5SAT3
- HORIZON_URL=https://horizon.stellar.org
- SPEND_LIMIT_CENTS=5000000
- PORT=8080

### Card Specs
- PAN: 9000002500000001
- AID: A000000999534645
- Spend Limit: $50,000.00
- Treasury Balance: $250,000.00 USD:GCVDS3
- Settlement TX: 6e938503bf9b1a2f8b43f5853b1e0982e09d27e26b06277e05bf906f9f66a087
- Parent UETR: 762adade-c02f-441a-a12b-e940c1c022a6
