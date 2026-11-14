package incari.client.skins;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ProfileTexturesTest {

    private static Property textures(String json, String signature) {
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return signature == null ? new Property("textures", value) : new Property("textures", value, signature);
    }

    @Test
    void readsASignedSkinAndCapeFromMojang() {
        MinecraftProfileTextures read = ProfileTextures.read(textures("""
                {"textures":{
                  "SKIN":{"url":"http://textures.minecraft.net/texture/abc","metadata":{"model":"slim"}},
                  "CAPE":{"url":"https://textures.minecraft.net/texture/def"}}}
                """, "sig"));

        assertThat(read.skin().getUrl()).isEqualTo("http://textures.minecraft.net/texture/abc");
        assertThat(read.skin().getMetadata("model")).isEqualTo("slim");
        assertThat(read.cape().getUrl()).isEqualTo("https://textures.minecraft.net/texture/def");
        assertThat(read.elytra()).isNull();
        assertThat(read.signatureState()).isEqualTo(SignatureState.SIGNED);
    }

    @Test
    void anUnsignedPropertyStaysUnsigned() {
        MinecraftProfileTextures read = ProfileTextures.read(
                textures("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/abc\"}}}", null));

        assertThat(read.skin()).isNotNull();
        assertThat(read.signatureState()).isEqualTo(SignatureState.UNSIGNED);
    }

    @Test
    void aTextureFromAnyOtherHostDropsThemAll() {
        MinecraftProfileTextures read = ProfileTextures.read(textures("""
                {"textures":{
                  "SKIN":{"url":"https://textures.minecraft.net/texture/abc"},
                  "CAPE":{"url":"https://evil.example/minecraft.net/cape.png"}}}
                """, "sig"));

        assertThat(read).isEqualTo(MinecraftProfileTextures.EMPTY);
    }

    @Test
    void badDataGivesNoTextures() {
        assertThat(ProfileTextures.read(new Property("textures", "not base64!")))
                .isEqualTo(MinecraftProfileTextures.EMPTY);
        assertThat(ProfileTextures.read(textures("[1,2]", null))).isEqualTo(MinecraftProfileTextures.EMPTY);
        assertThat(ProfileTextures.read(textures("{\"textures\":{}}", null)).skin())
                .isNull();
    }

    @Test
    void onlyMojangsDomainsOverHttp() {
        assertThat(ProfileTextures.fromMojang("https://textures.minecraft.net/texture/abc"))
                .isTrue();
        assertThat(ProfileTextures.fromMojang("HTTP://Textures.Minecraft.NET/texture/abc"))
                .isTrue();
        assertThat(ProfileTextures.fromMojang("https://minecraft.net.evil.example/a"))
                .isFalse();
        assertThat(ProfileTextures.fromMojang("https://evilminecraft.net/a")).isFalse();
        assertThat(ProfileTextures.fromMojang("file://textures.minecraft.net/a"))
                .isFalse();
        assertThat(ProfileTextures.fromMojang("not a url")).isFalse();
    }
}
