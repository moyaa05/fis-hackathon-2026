package com.fis.hackathon.audit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON "canónico" para el audit (SPEC §4.2): mismo contenido => mismos bytes => mismo hash. Siempre.
 * - claves ordenadas alfabéticamente en todos los niveles
 * - sin espacios
 * - las claves con valor null se omiten
 * - dinero (BigDecimal) como string con 2 decimales: "80000.00"
 * - prohibidos float/double (redondeos distintos = hash distinto) e Instant (formatead antes con AuditHasher)
 * Hecho a mano y sin Jackson a propósito: así no depende de la configuración de nadie.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(s, sb);
        } else if (v instanceof BigDecimal d) {
            string(d.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), sb);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof BigInteger) {
            sb.append(v);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Enum<?> e) {
            string(e.name(), sb);
        } else if (v instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, val) -> { if (val != null) sorted.put(String.valueOf(k), val); });
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                write(item, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported type in audit payload: " + v.getClass().getName()
                    + " (use String, BigDecimal, Integer/Long, Boolean, Map or List)");
        }
    }

    /** Mismo escapado que json.dumps(ensure_ascii=False) de Python (con el que se generaron los vectores). */
    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
