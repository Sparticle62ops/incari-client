package incari.client.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import incari.client.platform.OnlineServices;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.Services;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A profile's {@code textures} property read without Mojang's services. The game asks the session
 * service, which checks each texture's host against the list Mojang's discovery service sends and
 * the signature against keys fetched from Mojang. With Mojang's services off ({@link
 * OnlineServices}) neither list is there, so every skin and custom head would stay the default.
 *
 * <p>Here the hosts are checked against Mojang's own texture domains instead. The signature can't
 * be checked without Mojang's keys, so a signed property is taken on trust: a server can at worst
 * show another real Mojang skin, never an image from elsewhere.
 */
public final class ProfileTextures {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Where Mojang serves skins, capes and head textures. */
    private static final String[] MOJANG_DOMAINS = {".minecraft.net", ".mojang.com"};

    private ProfileTextures() {}

    /** Hook ({@code SkinManager}): the textures in {@code packed}, as the session service reads them. */
    public static MinecraftProfileTextures unpack(Services services, Property packed) {
        if (OnlineServices.isMojangAvailable()) {
            return services.sessionService().unpackTextures(packed);
        }
        return read(packed);
    }

    static MinecraftProfileTextures read(Property packed) {
        JsonObject textures;
        try {
            String json = new String(Base64.getDecoder().decode(packed.value()), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !(root.getAsJsonObject().get("textures") instanceof JsonObject found)) {
                return MinecraftProfileTextures.EMPTY;
            }
            textures = found;
        } catch (RuntimeException e) {
            // Bad base64 or JSON from a server: no textures, as the game treats it.
            LOGGER.warn("Couldn't read a profile's textures: {}", e.toString());
            return MinecraftProfileTextures.EMPTY;
        }
        MinecraftProfileTexture skin = texture(textures, "SKIN");
        MinecraftProfileTexture cape = texture(textures, "CAPE");
        MinecraftProfileTexture elytra = texture(textures, "ELYTRA");
        for (MinecraftProfileTexture texture : new MinecraftProfileTexture[] {skin, cape, elytra}) {
            if (texture != null && !fromMojang(texture.getUrl())) {
                // One texture from anywhere else and none of them are used, as the game does.
                LOGGER.warn("Profile textures name a host that isn't Mojang's: {}", texture.getUrl());
                return MinecraftProfileTextures.EMPTY;
            }
        }
        SignatureState signature = packed.hasSignature() ? SignatureState.SIGNED : SignatureState.UNSIGNED;
        return new MinecraftProfileTextures(skin, cape, elytra, signature);
    }

    private static @Nullable MinecraftProfileTexture texture(JsonObject textures, String type) {
        if (!(textures.get(type) instanceof JsonObject entry)) {
            return null;
        }
        String url = url(entry);
        if (url == null) {
            return null;
        }
        Map<String, String> metadata = new HashMap<>();
        if (entry.get("metadata") instanceof JsonObject values) {
            for (String key : values.keySet()) {
                if (values.get(key).isJsonPrimitive()) {
                    metadata.put(key, values.get(key).getAsString());
                }
            }
        }
        return new MinecraftProfileTexture(url, metadata);
    }

    private static @Nullable String url(JsonObject entry) {
        JsonElement url = entry.get("url");
        return url != null && url.isJsonPrimitive() ? url.getAsString() : null;
    }

    static boolean fromMojang(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String domain : MOJANG_DOMAINS) {
            if (host.endsWith(domain)) {
                return true;
            }
        }
        return false;
    }
}
