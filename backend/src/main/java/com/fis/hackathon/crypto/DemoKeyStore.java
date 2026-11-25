package com.fis.hackathon.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Llavero de la DEMO: lee demo-keys.json (classpath) una vez al arrancar.
 * Las claves son sintéticas y simulan las que cada empresa guarda en su propia wallet/HSM.
 * Solo SupplierSimulatorImpl puede pedir claves privadas (privateKey es package-private).
 */
@Component
public class DemoKeyStore {

    private record Entry(String publicKey, String privateKey) {}

    private final Map<String, Entry> keys;

    public DemoKeyStore(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("demo-keys.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            Map<String, Entry> map = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("_")) continue;   // "_warning"
                map.put(e.getKey(), new Entry(e.getValue().get("publicKey").asText(), e.getValue().get("privateKey").asText()));
            }
            this.keys = Map.copyOf(map);
        }
    }

    public boolean has(String walletAddress) {
        return keys.containsKey(walletAddress);
    }

    public String publicKey(String walletAddress) {
        return entry(walletAddress).publicKey();
    }

    PrivateKey privateKey(String walletAddress) {
        try {
            return Ed25519.privateKey(entry(walletAddress).privateKey());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid demo private key for " + walletAddress, e);
        }
    }

    private Entry entry(String walletAddress) {
        Entry e = keys.get(walletAddress);
        if (e == null) throw new IllegalArgumentException("No demo key for wallet " + walletAddress);
        return e;
    }
}
