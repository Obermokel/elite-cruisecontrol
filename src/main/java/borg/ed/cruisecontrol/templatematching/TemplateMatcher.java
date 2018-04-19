package borg.ed.cruisecontrol.templatematching;

import java.util.ArrayList;
import java.util.List;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.util.ImageUtil;

public class TemplateMatcher {

    public static TemplateMatch findBestMatchingLocation(GrayU8 image, GrayU8 template) {
        return TemplateMatcher.findBestMatchingLocation(image, template, null);
    }

    public static TemplateMatch findBestMatchingLocation(GrayU8 image, GrayU8 template, GrayF32 mask) {
        TemplateMatch bestMatch = null;

        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;
        for (int yInImage = 0; yInImage <= (image.height - template.height); yInImage++) {
            for (int xInImage = 0; xInImage <= (image.width - template.width); xInImage++) {
                float error = 0.0f;
                int pixels = 0;
                float errorPerPixel = 0;
                for (int yInTemplate = 0; yInTemplate < template.height && error < bestError && errorPerPixel < 5000; yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width && error < bestError && errorPerPixel < 5000; xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                        if (maskValue > 0) {
                            float diff = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) - template.unsafe_get(xInTemplate, yInTemplate);
                            error += (diff * diff) * maskValue;
                            pixels++;
                            errorPerPixel = error / pixels;
                        }
                    }
                }
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                }
            }
        }

        return bestMatch;
    }

    public static TemplateMatch findBestMatchingLocation(GrayF32 image, GrayF32 template) {
        return TemplateMatcher.findBestMatchingLocation(image, template, null);
    }

    public static TemplateMatch findBestMatchingLocation(GrayF32 image, GrayF32 template, GrayF32 mask) {
        TemplateMatch bestMatch = null;

        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;
        for (int yInImage = 0; yInImage <= (image.height - template.height); yInImage++) {
            for (int xInImage = 0; xInImage <= (image.width - template.width); xInImage++) {
                float error = 0.0f;
                int pixels = 0;
                for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate) / 255f;
                        if (maskValue > 0) {
                            float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                            float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                            float diff = vImage - vTemplate;
                            error += (diff * diff) * maskValue;
                            pixels++;
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                }
            }
        }

        return bestMatch;
    }

    public static TemplateMatch findBestMatchingLocationSmart(GrayF32 image, GrayF32 template, GrayF32 mask, int startX, int startY, float maxErrorPerPixel) {
        TemplateMatch bestMatch = null;
        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;

        final int minPixels = (template.width * template.height) / 25;

        final int minX = 0;
        final int maxX = image.width - template.width;
        final int minY = 0;
        final int maxY = image.height - template.height;

        int xInImage = Math.max(minX, Math.min(maxX, startX));
        int yInImage = Math.max(minY, Math.min(maxY, startY));
        int steps = 0;
        boolean finished = false;
        while (!finished) {
            if (steps == 0) {
                // Start x/y
                float error = 0.0f;
                int pixels = 0;
                for (int yInTemplate = 0; yInTemplate < template.height && error < bestError
                        && (pixels < minPixels || error / pixels < maxErrorPerPixel); yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width && error < bestError
                            && (pixels < minPixels || error / pixels < maxErrorPerPixel); xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                        if (maskValue > 0) {
                            float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                            float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                            float diff = vImage - vTemplate;
                            error += (diff * diff) * (maskValue / 255f);
                            pixels++;
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                }
                steps = 1;
            } else {
                // Circle around
                boolean foundRight = false;
                for (int step = 0; step < steps; step++) {
                    xInImage++;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundRight = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError
                                && (pixels < minPixels || error / pixels < maxErrorPerPixel); yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError
                                    && (pixels < minPixels || error / pixels < maxErrorPerPixel); xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }

                boolean foundDown = false;
                for (int step = 0; step < steps; step++) {
                    yInImage++;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundDown = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError
                                && (pixels < minPixels || error / pixels < maxErrorPerPixel); yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError
                                    && (pixels < minPixels || error / pixels < maxErrorPerPixel); xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }
                steps++;

                boolean foundLeft = false;
                for (int step = 0; step < steps; step++) {
                    xInImage--;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundLeft = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError
                                && (pixels < minPixels || error / pixels < maxErrorPerPixel); yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError
                                    && (pixels < minPixels || error / pixels < maxErrorPerPixel); xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }

                boolean foundUp = false;
                for (int step = 0; step < steps; step++) {
                    yInImage--;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundUp = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError
                                && (pixels < minPixels || error / pixels < maxErrorPerPixel); yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError
                                    && (pixels < minPixels || error / pixels < maxErrorPerPixel); xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }
                steps++;

                finished = !foundRight && !foundDown && !foundLeft && !foundUp;
            }
        }

        return bestMatch;
    }

    public static TemplateMatch findBestMatchingLocationSmart(GrayF32 image, GrayF32 template, GrayF32 mask, int startX, int startY) {
        TemplateMatch bestMatch = null;
        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;

        final int minX = 0;
        final int maxX = image.width - template.width;
        final int minY = 0;
        final int maxY = image.height - template.height;

        int xInImage = Math.max(minX, Math.min(maxX, startX));
        int yInImage = Math.max(minY, Math.min(maxY, startY));
        int steps = 0;
        boolean finished = false;
        while (!finished) {
            if (steps == 0) {
                // Start x/y
                float error = 0.0f;
                int pixels = 0;
                for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                        if (maskValue > 0) {
                            float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                            float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                            float diff = vImage - vTemplate;
                            error += (diff * diff) * (maskValue / 255f);
                            pixels++;
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                }
                steps = 1;
            } else {
                // Circle around
                boolean foundRight = false;
                for (int step = 0; step < steps; step++) {
                    xInImage++;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundRight = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }

                boolean foundDown = false;
                for (int step = 0; step < steps; step++) {
                    yInImage++;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundDown = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }
                steps++;

                boolean foundLeft = false;
                for (int step = 0; step < steps; step++) {
                    xInImage--;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundLeft = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }

                boolean foundUp = false;
                for (int step = 0; step < steps; step++) {
                    yInImage--;
                    if (xInImage >= minX && xInImage <= maxX && yInImage >= minY && yInImage <= maxY) {
                        foundUp = true;
                        float error = 0.0f;
                        int pixels = 0;
                        for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                            for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                                float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                                if (maskValue > 0) {
                                    float vImage = image.unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate) / 255f;
                                    float vTemplate = template.unsafe_get(xInTemplate, yInTemplate) / 255f;
                                    float diff = vImage - vTemplate;
                                    error += (diff * diff) * (maskValue / 255f);
                                    pixels++;
                                }
                            }
                        }
                        float errorPerPixel = error / pixels;
                        if (errorPerPixel < bestErrorPerPixel) {
                            bestError = error;
                            bestErrorPerPixel = errorPerPixel;
                            bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                        }
                    }
                }
                steps++;

                finished = !foundRight && !foundDown && !foundLeft && !foundUp;
            }
        }

        return bestMatch;
    }

    public static TemplateMatch findBestMatchingLocation(Planar<GrayF32> image, Planar<GrayF32> template) {
        return TemplateMatcher.findBestMatchingLocation(image, template, null);
    }

    public static TemplateMatch findBestMatchingLocation(Planar<GrayF32> image, Planar<GrayF32> template, GrayF32 mask) {
        TemplateMatch bestMatch = null;

        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;
        for (int yInImage = 0; yInImage <= (image.height - template.height); yInImage++) {
            for (int xInImage = 0; xInImage <= (image.width - template.width); xInImage++) {
                float error = 0.0f;
                int pixels = 0;
                for (int yInTemplate = 0; yInTemplate < template.height && error < bestError; yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width && error < bestError; xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                        if (maskValue > 0) {
                            for (int band = 0; band < image.bands.length; band++) {
                                float diff = image.getBand(band).unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate)
                                        - template.getBand(band).unsafe_get(xInTemplate, yInTemplate);
                                error += (diff * diff) * maskValue;
                                pixels++;
                            }
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel);
                }
            }
        }

        return bestMatch;
    }

    public static List<TemplateMatch> findAllMatchingLocations(Planar<GrayF32> image, Planar<GrayF32> template, GrayF32 mask, float maxErrorPerPixel) {
        List<TemplateMatch> matches = new ArrayList<>();

        for (int yInImage = 0; yInImage <= (image.height - template.height); yInImage++) {
            for (int xInImage = 0; xInImage <= (image.width - template.width); xInImage++) {
                float error = 0.0f;
                int pixels = 0;
                for (int yInTemplate = 0; yInTemplate < template.height; yInTemplate++) {
                    for (int xInTemplate = 0; xInTemplate < template.width; xInTemplate++) {
                        float maskValue = mask == null ? 1 : mask.unsafe_get(xInTemplate, yInTemplate);
                        if (maskValue > 0) {
                            for (int band = 0; band < image.bands.length; band++) {
                                float diff = image.getBand(band).unsafe_get(xInImage + xInTemplate, yInImage + yInTemplate)
                                        - template.getBand(band).unsafe_get(xInTemplate, yInTemplate);
                                error += (diff * diff) * maskValue;
                                pixels++;
                            }
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel <= maxErrorPerPixel) {
                    matches.add(new TemplateMatch(xInImage, yInImage, template.width, template.height, error, errorPerPixel));
                }
            }
        }

        return matches;
    }

    public static TemplateMatchRgb findBestMatchingTemplate(Planar<GrayF32> region, List<TemplateRgb> templates) {
        float bestError = 999999999.9f;
        float bestErrorPerPixel = 999999999.9f;
        float regionAR = (float) region.getWidth() / (float) region.getHeight();
        TemplateMatchRgb bestMatch = null;
        for (TemplateRgb t : templates) {
            float templateAR = (float) t.getWidth() / (float) t.getHeight();
            if (templateAR > regionAR * 1.25f || templateAR < regionAR / 1.25f) {
                // Too far off
            } else {
                Planar<GrayF32> scaledTemplatePixels = t.scalePixelsToSize(region.getWidth(), region.getHeight());
                GrayF32 scaledTemplateMask = t.scaleMaskToSize(region.getWidth(), region.getHeight());
                Planar<GrayF32> regionPixels = ImageUtil.normalize(region);
                float error = 0.0f;
                int pixels = 0;
                for (int band = 0; band < region.getNumBands(); band++) {
                    GrayF32 regionPixelsBand = regionPixels.getBand(band);
                    GrayF32 scaledTemplatePixelsBand = scaledTemplatePixels.getBand(band);
                    for (int y = 0; y < region.getHeight() && error < bestError; y++) {
                        for (int x = 0; x < region.getWidth() && error < bestError; x++) {
                            float mask = scaledTemplateMask == null ? 1 : scaledTemplateMask.unsafe_get(x, y);
                            if (mask > 0) {
                                float diff = regionPixelsBand.unsafe_get(x, y) - scaledTemplatePixelsBand.unsafe_get(x, y);
                                error += (diff * diff) * mask;
                                pixels++;
                            }
                        }
                    }
                }
                float errorPerPixel = error / pixels;
                if (errorPerPixel < bestErrorPerPixel) {
                    bestError = error;
                    bestErrorPerPixel = errorPerPixel;
                    bestMatch = new TemplateMatchRgb(region, t, 0, 0, region.getWidth(), region.getHeight(), error, errorPerPixel);
                }
            }
        }
        return bestMatch;
    }

}
