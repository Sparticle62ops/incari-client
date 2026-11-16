package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import incari.client.testing.TestPlatform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Biome colours sent as plain ints decode opaque. Old servers' biomes (ViaVersion's registry, and
 * vanilla servers up to 1.21) send {@code water_color} and the others as ints with no alpha byte; read
 * as they are, water, grass and leaves drew with alpha 0 when biome blend was off.
 */
class BiomeColorAlphaTest {

    @BeforeAll
    static void platform() {
        TestPlatform.install();
    }

    @Test
    void intColorsFromOldServersAreOpaque() {
        CompoundTag effects = new CompoundTag();
        effects.putInt("water_color", 0x3F76E4);
        effects.putInt("grass_color", 0x6A7039);
        effects.putInt("foliage_color", 0x6A7039);
        effects.putInt("dry_foliage_color", 0x7B5334);

        BiomeSpecialEffects decoded =
                BiomeSpecialEffects.CODEC.parse(NbtOps.INSTANCE, effects).getOrThrow();

        assertThat(decoded.waterColor()).isEqualTo(0xFF3F76E4);
        assertThat(decoded.grassColorOverride()).contains(0xFF6A7039);
        assertThat(decoded.foliageColorOverride()).contains(0xFF6A7039);
        assertThat(decoded.dryFoliageColorOverride()).contains(0xFF7B5334);
    }

    @Test
    void hexColorsDecodeAsBefore() {
        var json = JsonParser.parseString("{\"water_color\":\"#3f76e4\",\"grass_color\":\"#6a7039\"}");

        BiomeSpecialEffects decoded =
                BiomeSpecialEffects.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertThat(decoded.waterColor()).isEqualTo(0xFF3F76E4);
        assertThat(decoded.grassColorOverride()).contains(0xFF6A7039);
    }

    @Test
    void encodingStillWritesHex() {
        var decoded =
                BiomeSpecialEffects.CODEC.parse(NbtOps.INSTANCE, waterOnly()).getOrThrow();

        var encoded =
                BiomeSpecialEffects.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();

        assertThat(encoded.getAsJsonObject().get("water_color").getAsString()).isEqualToIgnoringCase("#3f76e4");
    }

    private static CompoundTag waterOnly() {
        CompoundTag effects = new CompoundTag();
        effects.putInt("water_color", 0x3F76E4);
        return effects;
    }
}
