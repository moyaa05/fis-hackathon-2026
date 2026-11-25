package com.fis.hackathon.shared;

/**
 * Resultado de comprobar la firma del proveedor (SPEC §3.3).
 * VALID              = firmada con la clave registrada del proveedor -> se libera.
 * SELF_AUTHORISATION = firmada con la clave de la wallet que pide el dinero (huella del atacante) -> se bloquea.
 * INVALID            = no casa con ninguna clave -> se cuenta el intento.
 * MALFORMED          = no es una firma Ed25519 (no es base64 o no mide 64 bytes) -> 400, no cambia nada.
 */
public enum RotationResult { VALID, SELF_AUTHORISATION, INVALID, MALFORMED }
