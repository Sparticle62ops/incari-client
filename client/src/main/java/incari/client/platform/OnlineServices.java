package incari.client.platform;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import java.net.Proxy;

/**
 * Which of Mojang's online services the platform offers. The browser build strips Realms (its HTTP
 * client needs raw sockets and Mojang sign-in), so the entry point switches it off and the game
 * answers Realms' availability check without calling out, as it does in offline developer mode.
 *
 * <p>The browser build also never calls Mojang's services on its own: no service discovery, profile,
 * user property, key or telemetry requests (each one a failed or CORS-blocked request on a school
 * network). Microsoft sign-in in Edit Profile still talks to Mojang, only when the player asks.
 */
public final class OnlineServices {

    private static volatile boolean realms = true;
    private static volatile boolean mojang = true;

    private OnlineServices() {}

    /** Switches Realms off; call before the game starts. */
    public static void disableRealms() {
        realms = false;
    }

    /** Switches Mojang's services off; call before the game starts. */
    public static void disableMojang() {
        mojang = false;
    }

    /** {@code RealmsAvailability.check}: whether to ask the Realms service at all. */
    public static boolean isRealmsAvailable() {
        return realms;
    }

    /** Whether the game may call Mojang's services. */
    public static boolean isMojangAvailable() {
        return mojang;
    }

    /**
     * Hook ({@code Minecraft}'s constructor): the game's discovery service. Off, it's the offline one,
     * which never fetches; the online one starts fetching (and retrying) the moment it's made.
     */
    public static MinecraftServicesDiscoveryService discovery(Proxy proxy, boolean keys) {
        return mojang
                ? MinecraftServicesDiscoveryService.create(proxy, keys)
                : MinecraftServicesDiscoveryService.createOffline(proxy);
    }
}
