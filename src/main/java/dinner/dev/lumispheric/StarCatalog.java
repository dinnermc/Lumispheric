package dinner.dev.lumispheric;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class StarCatalog {
    private static final double STAR_RADIUS = 100.0;
    private static final List<Star> STARS = load();

    private StarCatalog() {}

    static MeshData buildMesh() {
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS,
            DefaultVertexFormat.POSITION_COLOR
        );

        for (Star star : STARS) {
            double cosDeclination = Math.cos(star.declination);
            double sinDeclination = Math.sin(star.declination);
            float magnitude = clamp((6.0F - star.magnitude) / 7.5F, 0.0F, 1.0F);
            float intensity = 0.08F + magnitude * 0.92F;
            double equatorialX = cosDeclination * Math.cos(star.rightAscension);
            double equatorialY = cosDeclination * Math.sin(star.rightAscension);
            double equatorialZ = sinDeclination;
            addStar(
                builder, equatorialX, equatorialY, equatorialZ,
                0.04F + magnitude * 0.09F, star.colorIndex, intensity
            );
        }

        return builder.buildOrThrow();
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
        float red = clamp(1.0F + Math.min(0.0F, colorIndex) * 0.20F, 0.84F, 1.0F);
        float green = clamp(1.0F - Math.max(0.0F, colorIndex) * 0.08F, 0.86F, 1.0F);
        float blue = clamp(1.0F - Math.max(0.0F, colorIndex) * 0.16F, 0.68F, 1.0F);

        addVertex(builder, centerX - rightX - verticalX, centerY - rightY - verticalY,
            centerZ - rightZ - verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX + rightX - verticalX, centerY + rightY - verticalY,
            centerZ + rightZ - verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX + rightX + verticalX, centerY + rightY + verticalY,
            centerZ + rightZ + verticalZ, red, green, blue, intensity);
        addVertex(builder, centerX - rightX + verticalX, centerY - rightY + verticalY,
            centerZ - rightZ + verticalZ, red, green, blue, intensity);
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

    private record Star(double rightAscension, double declination, float magnitude, float colorIndex) {}
}
