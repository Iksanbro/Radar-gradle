package dev.radarfcs.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.radarfcs.FcsMod;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * Вид подшипника орудия по поворотному подшипнику Create Aeronautics. Их ассеты - All Rights Reserved, поэтому в наш
 * jar ничего не копируется: при запуске клиента их текстура и модель читаются из установленного мода Simulated
 * (в составе Aeronautics), текстура перекрашивается в чёрный с сохранением светотени, на верхних гранях рамки
 * плиты (сторона, к которой крепится поворотная часть) - жёлто-чёрные полосы, и всё кладётся в пакет ресурсов в папке
 * игры ({@code radar_fcs/aeronautics_bearing_pack}). Модели пакета - их модели с нашей текстурой.
 */
public final class AeroBearingLook {
    private static final String TEX = "assets/simulated/textures/block/swivel_bearing.png";
    private static final String MODEL = "assets/simulated/models/block/swivel_bearing/block.json";

    private AeroBearingLook() {}

    /** Собрать пакет; папка пакета или null (Aeronautics нет или его ассеты не читаются). */
    @Nullable
    public static Path build() {
        try {
            var file = ModList.get().getModFileById("simulated");
            if (file == null) return null;
            Path tex = file.getFile().findResource(TEX), model = file.getFile().findResource(MODEL);
            if (!Files.exists(tex) || !Files.exists(model)) return null;
            Path dir = FMLPaths.GAMEDIR.get().resolve(FcsMod.MODID).resolve("aeronautics_bearing_pack");
            Path texOut = dir.resolve("assets/radar_fcs/textures/block/gun_bearing_aero.png");
            Path models = dir.resolve("assets/radar_fcs/models");
            Files.createDirectories(texOut.getParent());
            Files.createDirectories(models.resolve("block"));
            Files.createDirectories(models.resolve("item"));
            List<int[]> stripes = hazardRects(Files.readString(model, StandardCharsets.UTF_8));
            try (InputStream in = Files.newInputStream(tex); NativeImage img = NativeImage.read(in)) {
                recolor(img, stripes);
                img.writeToFile(texOut);
            }
            String t = "\"textures\": {\"2\": \"radar_fcs:block/gun_bearing_aero\", \"particle\": \"radar_fcs:block/gun_bearing_aero\"}";
            Files.writeString(models.resolve("block/gun_bearing.json"), "{\"parent\": \"simulated:block/swivel_bearing/block\", " + t + "}");
            Files.writeString(models.resolve("item/gun_bearing.json"), "{\"parent\": \"simulated:block/swivel_bearing/item\", " + t + "}");
            Files.writeString(models.resolve("block/gun_bearing_cog.json"), "{\"parent\": \"simulated:block/swivel_bearing/ironcog\", " + t + "}");
            // собранный: их нижняя часть; пластина на поворотной части - их пластина
            Files.writeString(models.resolve("block/gun_bearing_assembled.json"), "{\"parent\": \"simulated:block/swivel_bearing/block_assembled\", " + t + "}");
            Files.writeString(models.resolve("block/gun_bearing_plate.json"), "{\"parent\": \"simulated:block/swivel_bearing/bearing_plate\", " + t + "}");
            Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\": {\"pack_format\": 34, \"description\": "
                    + "\"Radar FCS: gun bearing in the Create Aeronautics look (generated locally from the installed mod)\"}}");
            return dir;
        } catch (Throwable e) {
            FcsMod.LOG.warn("[radar_fcs] gun bearing Aeronautics look not built: {}", e.toString());
            return null;
        }
    }

    /**
     * Прямоугольники текстуры (в долях 0..16, как uv модели) верхних граней рамки плиты: элементы, доходящие до верха
     * блока (to.y = 16), их грань up.
     */
    static List<int[]> hazardRects(String modelJson) {
        List<int[]> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(modelJson).getAsJsonObject();
        if (!root.has("elements")) return out;
        for (JsonElement e : root.getAsJsonArray("elements")) {
            JsonObject el = e.getAsJsonObject();
            JsonArray to = el.getAsJsonArray("to");
            if (to == null || to.get(1).getAsDouble() < 15.99) continue;
            JsonObject faces = el.getAsJsonObject("faces");
            if (faces == null || !faces.has("up")) continue;
            JsonObject up = faces.getAsJsonObject("up");
            if (!up.has("uv")) continue;
            JsonArray uv = up.getAsJsonArray("uv");
            double u1 = uv.get(0).getAsDouble(), v1 = uv.get(1).getAsDouble(), u2 = uv.get(2).getAsDouble(), v2 = uv.get(3).getAsDouble();
            out.add(new int[]{(int) Math.floor(Math.min(u1, u2) * 100), (int) Math.floor(Math.min(v1, v2) * 100),
                    (int) Math.ceil(Math.max(u1, u2) * 100), (int) Math.ceil(Math.max(v1, v2) * 100)});
        }
        return out;
    }

    /** Чёрный со светотенью оригинала; в прямоугольниках stripes - жёлто-чёрные диагональные полосы. */
    static void recolor(NativeImage img, List<int[]> stripes) {
        int w = img.getWidth(), h = img.getHeight();
        double sx = w / 16.0, sy = h / 16.0;
        int band = Math.max(1, w / 16);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);                          // ABGR
                int a = c >>> 24;
                if (a == 0) continue;
                int r = c & 255, g = (c >> 8) & 255, b = (c >> 16) & 255;
                double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                boolean hazard = false;
                for (int[] s : stripes) {
                    if (x >= s[0] / 100.0 * sx && x < s[2] / 100.0 * sx && y >= s[1] / 100.0 * sy && y < s[3] / 100.0 * sy) {
                        hazard = true;
                        break;
                    }
                }
                int nr, ng, nb;
                if (hazard && ((x + y) / (2 * band)) % 2 == 0) {
                    double k = 0.55 + 0.6 * lum;                          // жёлтая полоса с тенями оригинала
                    nr = clamp(255 * k); ng = clamp(196 * k); nb = clamp(20 * k);
                } else {
                    int v = clamp(12 + 105 * lum);                       // чёрный металл: тёмно-серые оттенки
                    nr = v; ng = v; nb = clamp(v * 1.06);
                }
                img.setPixelRGBA(x, y, (a << 24) | (nb << 16) | (ng << 8) | nr);
            }
        }
    }

    private static int clamp(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }
}
