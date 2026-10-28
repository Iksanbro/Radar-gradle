package dev.radarfcs.core;

import java.util.Set;

/**
 * Правило «свой-чужой» вычислителя по кодам транспондеров.
 * Белый список: свои - только транспондеры с кодом из списка, остальные транспондеры ничего не защищают.
 * Чёрный список: свои - все транспондеры, кроме кодов из списка.
 */
public record IffRule(boolean whitelist, Set<String> codes) {
    /** По умолчанию: чёрный список без кодов - свои все транспондеры. */
    public static final IffRule DEFAULT = new IffRule(false, Set.of());

    public IffRule {
        codes = Set.copyOf(codes);
    }

    /** Свой ли транспондер с этим кодом ("" - без кода). */
    public boolean accepts(String code) {
        String c = code == null ? "" : code;
        return whitelist ? !c.isEmpty() && codes.contains(c) : !codes.contains(c);
    }
}
