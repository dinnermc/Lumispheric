package dinner.dev.lumispheric;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class StarCatalog extends SimplePreparableReloadListener<List<StarCatalog.Star>> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumispheric");
    private static final double STAR_RADIUS = 100.0;
    private static final Codec<StarData> STAR_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.DOUBLE.fieldOf("ra_hours").forGetter(StarData::rightAscensionHours),
        Codec.DOUBLE.fieldOf("dec_degrees").forGetter(StarData::declinationDegrees),
        Codec.FLOAT.fieldOf("magnitude").forGetter(StarData::magnitude),
        Codec.FLOAT.fieldOf("color_index").forGetter(StarData::colorIndex)
    ).apply(instance, StarData::new));
    private static final Codec<StarFile> FILE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("replace", false).forGetter(StarFile::replace),
        STAR_CODEC.listOf().fieldOf("stars").forGetter(StarFile::stars)
    ).apply(instance, StarFile::new));
    static final StarCatalog INSTANCE = new StarCatalog();
    private static final List<Star> DEFAULT_STARS = load();
    private static volatile List<Star> stars = DEFAULT_STARS;
    private static volatile long revision;

    private StarCatalog() {}

    static long revision() {
        return revision;
    }

    static MeshData buildMesh() {
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS,
            DefaultVertexFormat.POSITION_COLOR
        );

        for (Star star : stars) {
            double cosDeclination = Math.cos(star.declination);
            double sinDeclination = Math.sin(star.declination);
            double equatorialX = cosDeclination * Math.cos(star.rightAscension);
            double equatorialY = cosDeclination * Math.sin(star.rightAscension);
            double equatorialZ = sinDeclination;
            float magnitude = clamp((6.0F - star.magnitude) / 7.5F, 0.0F, 1.0F);
            float intensity = 0.20F + magnitude * 0.80F;
            addStar(
                builder, equatorialX, equatorialY, equatorialZ,
                0.06F + magnitude * 0.08F, star.colorIndex, intensity
            );
        }

        return builder.buildOrThrow();
    }

    @Override
    protected List<Star> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<Star> loadedStars = new ArrayList<>(DEFAULT_STARS);
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(
                "stars",
                location -> location.getNamespace().equals(Lumispheric.MODID)
                    && location.getPath().endsWith(".json")
            );

        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> appendResource(entry.getKey(), entry.getValue(), loadedStars));
        return List.copyOf(loadedStars);
    }

    @Override
    protected void apply(List<Star> loadedStars, ResourceManager resourceManager, ProfilerFiller profiler) {
        stars = List.copyOf(loadedStars);
        revision++;
    }

    private static void appendResource(
        ResourceLocation location,
        Resource resource,
        List<Star> loadedStars
    ) {
        StarFile starFile;
        try (Reader reader = resource.openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            var result = FILE_CODEC.parse(JsonOps.INSTANCE, json);
            if (result.error().isPresent()) {
                LOGGER.warn("Invalid star catalog {}: {}", location, result.error().orElseThrow().message());
                return;
            }
            starFile = result.result().orElseThrow();
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            LOGGER.warn("Could not read star catalog {}; keeping the remaining catalogs.", location, exception);
            return;
        }
        if (starFile == null) {
            return;
        }

        List<Star> parsedStars = new ArrayList<>(starFile.stars().size());
        try {
            for (StarData star : starFile.stars()) {
                parsedStars.add(star.toStar());
            }
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid star entry in catalog {}; keeping the remaining catalogs.", location, exception);
            return;
        }
        if (parsedStars.isEmpty()) {
            LOGGER.warn("Star catalog {} is empty; keeping the remaining catalogs.", location);
            return;
        }

        if (starFile.replace()) {
            loadedStars.clear();
        }
        loadedStars.addAll(parsedStars);
    }

    private static void addStar(
        BufferBuilder builder,
        double east,
        double up,
        double south,
        float halfSize,
        float colorIndex,
        float intensity
    ) {
        double directionLength = Math.sqrt(east * east + up * up + south * south);
        double directionX = east / directionLength;
        double directionY = up / directionLength;
        double directionZ = south / directionLength;
        double rightX = Math.abs(directionY) > 0.95 ? 0.0 : -directionZ;
        double rightY = Math.abs(directionY) > 0.95 ? directionZ : 0.0;
        double rightZ = Math.abs(directionY) > 0.95 ? -directionY : directionX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        rightX = rightX / rightLength * halfSize;
        rightY = rightY / rightLength * halfSize;
        rightZ = rightZ / rightLength * halfSize;
        double verticalX = rightY * directionZ - rightZ * directionY;
        double verticalY = rightZ * directionX - rightX * directionZ;
        double verticalZ = rightX * directionY - rightY * directionX;
        double verticalLength = Math.sqrt(verticalX * verticalX + verticalY * verticalY + verticalZ * verticalZ);
        verticalX = verticalX / verticalLength * halfSize;
        verticalY = verticalY / verticalLength * halfSize;
        verticalZ = verticalZ / verticalLength * halfSize;
        double centerX = directionX * STAR_RADIUS;
        double centerY = directionY * STAR_RADIUS;
        double centerZ = directionZ * STAR_RADIUS;
        float[] rgb = bvToRgb(colorIndex);
        float red = rgb[0];
        float green = rgb[1];
        float blue = rgb[2];

        addVertex(builder, centerX - rightX - verticalX, centerY - rightY - verticalY,
            centerZ - rightZ - verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX + rightX - verticalX, centerY + rightY - verticalY,
            centerZ + rightZ - verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX + rightX + verticalX, centerY + rightY + verticalY,
            centerZ + rightZ + verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX - rightX + verticalX, centerY - rightY + verticalY,
            centerZ - rightZ + verticalZ, red, green, blue, intensity);
    }

    private static float[] bvToRgb(float bvInput) {
        // Ballesteros B-V to effective temperature, mapped to a natural (softly desaturated) starlight RGB.
        float bv = clamp(bvInput, -0.4F, 2.0F);
        float r;
        float g;
        float b;
        if (bv < 0.0F) {
            float t = (bv + 0.4F) / 0.4F;
            r = 0.62F + 0.34F * t;
            g = 0.76F + 0.22F * t;
            b = 1.00F;
        } else if (bv < 0.4F) {
            float t = bv / 0.4F;
            r = 0.96F + 0.04F * t;
            g = 0.98F - 0.01F * t;
            b = 1.00F - 0.08F * t;
        } else if (bv < 1.0F) {
            float t = (bv - 0.4F) / 0.6F;
            r = 1.00F;
            g = 0.97F - 0.14F * t;
            b = 0.92F - 0.32F * t;
        } else {
            float t = (bv - 1.0F) / 1.0F;
            r = 1.00F;
            g = 0.83F - 0.28F * t;
            b = 0.60F - 0.34F * t;
        }
        // Subtle softening toward white so star colors remain authentic to the naked human eye.
        float saturation = 0.78F;
        r = 1.0F - (1.0F - r) * saturation;
        g = 1.0F - (1.0F - g) * saturation;
        b = 1.0F - (1.0F - b) * saturation;
        return new float[] { clamp(r, 0.0F, 1.0F), clamp(g, 0.0F, 1.0F), clamp(b, 0.0F, 1.0F) };
    }

    private static void addVertex(
        BufferBuilder builder,
        double x,
        double y,
        double z,
        float red,
        float green,
        float blue,
        float intensity
    ) {
        builder.addVertex((float) x, (float) y, (float) z).setColor(
            red * intensity,
            green * intensity,
            blue * intensity,
            1.0F
        );
    }

    private static List<Star> load() {
        InputStream resource = StarCatalog.class.getResourceAsStream("/assets/lumispheric/stars.csv");
        if (resource == null) {
            throw new IllegalStateException("Missing Lumispheric star catalog resource");
        }

        List<Star> stars = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.US_ASCII))) {
            String header = reader.readLine();
            if (!"ra_hours,dec_degrees,magnitude,color_index".equals(header)) {
                throw new IllegalStateException("Unexpected Lumispheric star catalog header");
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] fields = line.split(",", -1);
                if (fields.length != 4) {
                    throw new IllegalStateException("Malformed Lumispheric star catalog row " + lineNumber);
                }

                try {
                    double rightAscension = Double.parseDouble(fields[0]) * Math.PI / 12.0;
                    double declination = Math.toRadians(Double.parseDouble(fields[1]));
                    float magnitude = Float.parseFloat(fields[2]);
                    float colorIndex = Float.parseFloat(fields[3]);
                    stars.add(new Star(rightAscension, declination, magnitude, colorIndex));
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("Invalid Lumispheric star catalog row " + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Lumispheric star catalog", exception);
        }

        if (stars.isEmpty()) {
            throw new IllegalStateException("Lumispheric star catalog contains no stars");
        }
        return List.copyOf(stars);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record StarFile(boolean replace, List<StarData> stars) {}

    private record StarData(
        double rightAscensionHours, double declinationDegrees, float magnitude, float colorIndex
    ) {
        private Star toStar() {
            if (!Double.isFinite(rightAscensionHours) || rightAscensionHours < 0.0
                || rightAscensionHours >= 24.0 || !Double.isFinite(declinationDegrees)
                || declinationDegrees < -90.0 || declinationDegrees > 90.0
                || !Float.isFinite(magnitude) || !Float.isFinite(colorIndex)) {
                throw new IllegalArgumentException("Coordinates and stellar values must be finite and in range");
            }
            return new Star(
                rightAscensionHours * Math.PI / 12.0,
                Math.toRadians(declinationDegrees),
                magnitude,
                colorIndex
            );
        }
    }

    record Star(double rightAscension, double declination, float magnitude, float colorIndex) {}
}
