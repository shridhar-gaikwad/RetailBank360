package org.retailbank360.common.crypto;

/**
 * Static bridge that hands the Spring managed {@link CryptoService} to JPA attribute converters.
 *
 * <p>Converters are instantiated by the persistence provider rather than by Spring, so they cannot
 * rely on constructor injection. The auto-configuration publishes the service here during startup,
 * before any entity is read or written.</p>
 */
public final class CryptoHolder {

    private static volatile CryptoService cryptoService;

    private CryptoHolder() {
    }

    public static void set(CryptoService service) {
        cryptoService = service;
    }

    public static CryptoService get() {
        CryptoService service = cryptoService;
        if (service == null) {
            throw new IllegalStateException(
                    "CryptoService is not initialised yet. Ensure retailbank-common auto-configuration is active.");
        }
        return service;
    }

    /** Convenience accessor used by services that need a blind index outside an entity. */
    public static String blindIndex(String value) {
        return get().blindIndex(value);
    }
}
