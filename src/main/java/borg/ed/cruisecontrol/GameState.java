package borg.ed.cruisecontrol;

public enum GameState {

    UNKNOWN,

    /**
     * Next system ahead, charging but not in jump yet, so keep aligning to the next system.
     */
    FSD_CHARGING,

    /**
     * As soon as the FSD jump countdown starts we are in hyperspace. Throttle to 0%, then wait until arrived.
     */
    IN_HYPERSPACE,

    /**
     * Honk and wait until bodies discovered.
     */
    HONKING,

    /**
     * Accelerate to 25% and wait until we are scooping fuel.
     */
    GET_IN_SCOOPING_RANGE,

    /**
     * Throttle to 0% and wait until full.
     */
    SCOOPING_FUEL,

    /**
     * Turn nose down until sight is clear.
     */
    ALIGN_TO_STAR_ESCAPE,

    /**
     * At 25% throttle escape, again turning the nose down if there is too much brightness ahead. Do until not in fuel scoop range any more.
     */
    ESCAPE_FROM_STAR_SLOW,

    /**
     * At 75% throttle escape for 10 seconds, again turning the nose down if there is too much brightness ahead.
     */
    ESCAPE_FROM_STAR_FASTER,

    /**
     * At 100% throttle align to the next system.
     */
    ALIGN_TO_NEXT_SYSTEM;

}
