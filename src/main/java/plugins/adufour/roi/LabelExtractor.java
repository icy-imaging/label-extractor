/*
 * Copyright (c) 2010-2026. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.adufour.roi;

import org.bioimageanalysis.extension.kernel.roi.roi2d.ROI2DArea;
import org.bioimageanalysis.extension.kernel.roi.roi3d.ROI3DArea;
import org.bioimageanalysis.icy.common.collection.array.Array1DUtil;
import org.bioimageanalysis.icy.common.type.DataType;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginName;
import org.bioimageanalysis.icy.model.image.IcyBufferedImage;
import org.bioimageanalysis.icy.model.roi.ROI;
import org.bioimageanalysis.icy.model.sequence.Sequence;
import plugins.adufour.ezplug.*;
import plugins.adufour.vars.lang.VarROIArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Toolbox to extract regions of interest (ROI) from a labeled image or sequence based on its
 * connected components.<br>
 * This toolbox supersedes the connected components toolbox
 *
 * @author Alexandre Dufour
 */
@IcyPluginName("Label Extractor")
public class LabelExtractor extends EzPlug implements EzStoppable {
    EzVarSequence inSeq = new EzVarSequence("Labeled sequence");

    EzVarEnum<ExtractionType> type = new EzVarEnum<>("Extract", ExtractionType.values());

    EzVarDouble value = new EzVarDouble("Value");

    EzVarSequence outSeq = new EzVarSequence("Add ROI to");

    VarROIArray outROI = new VarROIArray("Extracted ROI");

    /**
     * List of extraction methods suitable for the "ROI Extractor" toolbox
     *
     * @author Alexandre Dufour
     */
    public enum ExtractionType {
        /**
         * Extract all connected components with a value that is different from the background.<br>
         * NB: Touching components with different labels will be <u>fused together</u>
         */
        ANY_LABEL_VS_BACKGROUND,
        /**
         * Extract all connected components with a value that is different from the background.<br>
         * NB: Touching components with different labels are extracted <u>separately</u>
         */
        ALL_LABELS_VS_BACKGROUND,
        /**
         * Extracts all components with a specific value
         */
        SPECIFIC_LABEL;

        @Override
        public String toString() {
            final String name = super.toString();
            return name.charAt(0) + name.substring(1).toLowerCase().replace('_', ' ');
        }
    }

    @Override
    public void clean() {

    }

    @Override
    protected void execute() {
        final List<ROI> rois = extractLabels(inSeq.getValue(true), type.getValue(), value.getValue());

        if (getUI() != null) {
            final Sequence outputSequence = outSeq.getValue(true);

            outputSequence.beginUpdate();
            for (final ROI roi : rois)
                outputSequence.addROI(roi);
            outputSequence.endUpdate();
        }
        else {
            outROI.setValue(rois.toArray(new ROI[0]));
        }
    }

    @Override
    protected void initialize() {
        addEzComponent(inSeq);
        addEzComponent(type);
        addEzComponent(value);
        addEzComponent(outSeq);
    }

    /**
     * A temporary component structure used during the extraction process. Many such components may
     * be extracted from the sequence, and some are fused into the final extracted ROI
     *
     * @author Alexandre Dufour
     */
    private static class ConnectedComponent {
        final double imageValue;

        /**
         * final label that should replace the current label if fusion is needed
         */
        int targetLabel;

        /**
         * if non-null, indicates the parent object with which the current object should be fused
         */
        ConnectedComponent targetComponent;

        /**
         * Creates a new label with the given value. If no parent is set to this label, the given
         * value will be the final one
         *
         * @param value the pixel value
         * @param label the label value
         */
        ConnectedComponent(final double value, final int label) {
            this.imageValue = value;
            this.targetLabel = label;
        }

        /**
         * Retrieves the final object label (recursively)
         */
        int getTargetLabel() {
            return targetComponent == null ? targetLabel : targetComponent.getTargetLabel();
        }
    }

    /**
     * Extract ROI in the specified sequence though its connected components (8-connectivity is
     * considered in 2D, and 26-connectivity in 3D). The algorithm uses a fast two-pass image
     * sweeping technique that builds a list of intermediate connected components which are
     * eventually fused according to their connectivity.
     *
     * @param sequence      the sequence to extract connected components from
     * @param whatToExtract the type of extraction to conduct (see the {@link ExtractionType} enumeration for
     *                      available options
     * @param value         this value is interpreted differently depending on the <code>whatToExtract</code>
     *                      parameter:<br>
     *                      <ul>
     *                      <li>{@link ExtractionType#ALL_LABELS_VS_BACKGROUND}: the value is that of the
     *                      background</li>
     *                      <li>{@link ExtractionType#ANY_LABEL_VS_BACKGROUND}: the value is that of the
     *                      background</li>
     *                      <li>{@link ExtractionType#SPECIFIC_LABEL}: the value is that of the structures to
     *                      extract</li>
     *                      </ul>
     * @return List of ROI
     */
    public static List<ROI> extractLabels(final Sequence sequence, final ExtractionType whatToExtract, final double value) {
        final ArrayList<ROI> roi = new ArrayList<>();

        int objID = 1;

        for (int t = 0; t < sequence.getSizeT(); t++)
            for (int c = 0; c < sequence.getSizeC(); c++) {
                for (final ROI label : extractLabels(sequence, t, c, whatToExtract, value)) {
                    // rename each ROI, but replace the ID
                    final String shortName = label.getName().substring(label.getName().indexOf(" ("));
                    label.setName("Object #" + objID++ + shortName);
                    roi.add(label);
                }
            }

        return roi;
    }

    /**
     * Extract ROI in the specified sequence though its connected components (8-connectivity is
     * considered in 2D, and 26-connectivity in 3D). The algorithm uses a fast single-pass sweeping
     * technique that builds a list of intermediate connected components which are eventually fused
     * according to their connectivity.<br>
     * NB: in 3D, the algorithm caches some intermediate buffers to disk to save RAM
     *
     * @param sequence      the sequence to extract connected components from
     * @param t             a specific time point where components should be extracted
     * @param c             a specific channel where components should be extracted
     * @param whatToExtract the type of extraction to conduct (see the {@link ExtractionType} enumeration for
     *                      available options
     * @param value         this value is interpreted differently depending on the <code>whatToExtract</code>
     *                      parameter:<br>
     *                      <ul>
     *                      <li>{@link ExtractionType#ALL_LABELS_VS_BACKGROUND}: the value is that of the
     *                      background</li>
     *                      <li>{@link ExtractionType#ANY_LABEL_VS_BACKGROUND}: the value is that of the
     *                      background</li>
     *                      <li>{@link ExtractionType#SPECIFIC_LABEL}: the value is that of the structures to
     *                      extract</li>
     *                      </ul>
     * @return List of ROI
     */
    public static List<ROI> extractLabels(final Sequence sequence, final int t, final int c, final ExtractionType whatToExtract, final double value) {
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int slice = width * height;
        final int depth = sequence.getSizeZ();
        final boolean is3D = depth > 1;
        final DataType dataType = sequence.getDataType();

        final Map<Integer, ConnectedComponent> ccs = new HashMap<>();
        final Map<Integer, ROI> roiMap = new HashMap<>();

        final int[] neighborLabels = new int[13];
        int nbNeighbors = 0;

        final boolean extractUserValue = (whatToExtract == ExtractionType.SPECIFIC_LABEL);

        // temporary label buffer
        final Sequence labelSequence = new Sequence();
        boolean virtual = false;

        int[] _labelsAbove = null;

        // first image pass: naive labeling with simple backward neighborhood
        int highestKnownLabel = 0;

        for (int z = 0; z < depth; z++) {
            int[] _labelsHere;

            try {
                _labelsHere = new int[slice];
            }
            catch (final OutOfMemoryError error) {
                // not enough memory --> pass in virtual mode
                if (!virtual) {
                    labelSequence.setVolatile(true);
                    virtual = true;

                    // re-allocate (we should have enough memory now)
                    _labelsHere = new int[slice];
                }
                else
                    throw error;
            }

            // if (is3D) System.out.println("[Label Extractor] First pass (Z" + z + ")");

            final Object inputData = sequence.getDataXY(t, z, c);

            for (int y = 0, inOffset = 0; y < height; y++) {
                if (Thread.currentThread().isInterrupted())
                    return new ArrayList<>();

                for (int x = 0; x < width; x++, inOffset++) {
                    final double currentImageValue = Array1DUtil.getValue(inputData, inOffset, dataType);
                    final boolean pixelEqualsUserValue = (currentImageValue == value);

                    // do not process the current pixel if:
                    // - extractUserValue is true and pixelEqualsUserValue is false
                    // - extractUserValue is false and pixelEqualsUserValue is true

                    if (extractUserValue != pixelEqualsUserValue)
                        continue;

                    // from here on, the current pixel should be labeled

                    // -> look for existing labels in its neighborhood

                    // 1) define the neighborhood of interest here
                    // NB: this is a single pass method, so backward neighborhood is sufficient

                    // legend:
                    // e = edge
                    // x = current pixel
                    // n = valid neighbor
                    // . = other neighbor

                    if (z == 0) {
                        if (y == 0) {
                            if (x == 0) {
                                // e e e
                                // e x .
                                // e . .

                                // do nothing
                            }
                            else {
                                // e e e
                                // n x .
                                // . . .

                                neighborLabels[0] = _labelsHere[inOffset - 1];
                                nbNeighbors = 1;
                            }
                        }
                        else {
                            final int north = inOffset - width;

                            if (x == 0) {
                                // e n n
                                // e x .
                                // e . .

                                neighborLabels[0] = _labelsHere[north];
                                neighborLabels[1] = _labelsHere[north + 1];
                                nbNeighbors = 2;
                            }
                            else if (x == width - 1) {
                                // n n e
                                // n x e
                                // . . e

                                neighborLabels[0] = _labelsHere[north - 1];
                                neighborLabels[1] = _labelsHere[north];
                                neighborLabels[2] = _labelsHere[inOffset - 1];
                                nbNeighbors = 3;
                            }
                            else {
                                // n n n
                                // n x .
                                // . . .

                                neighborLabels[0] = _labelsHere[north - 1];
                                neighborLabels[1] = _labelsHere[north];
                                neighborLabels[2] = _labelsHere[north + 1];
                                neighborLabels[3] = _labelsHere[inOffset - 1];
                                nbNeighbors = 4;
                            }
                        }
                    }
                    else {
                        if (y == 0) {
                            final int south = inOffset + width;

                            if (x == 0) {
                                // e e e | e e e
                                // e n n | e x .
                                // e n n | e . .

                                neighborLabels[0] = _labelsAbove[inOffset];
                                neighborLabels[1] = _labelsAbove[inOffset + 1];
                                neighborLabels[2] = _labelsAbove[south];
                                neighborLabels[3] = _labelsAbove[south + 1];
                                nbNeighbors = 4;
                            }
                            else if (x == width - 1) {
                                // e e e | e e e
                                // n n e | n x e
                                // n n e | . . e

                                neighborLabels[0] = _labelsAbove[inOffset - 1];
                                neighborLabels[1] = _labelsAbove[inOffset];
                                neighborLabels[2] = _labelsAbove[south - 1];
                                neighborLabels[3] = _labelsAbove[south];
                                neighborLabels[4] = _labelsHere[inOffset - 1];
                                nbNeighbors = 5;
                            }
                            else {
                                // e e e | e e e
                                // n n n | n x .
                                // n n n | . . .

                                neighborLabels[0] = _labelsAbove[inOffset - 1];
                                neighborLabels[1] = _labelsAbove[inOffset];
                                neighborLabels[2] = _labelsAbove[inOffset + 1];
                                neighborLabels[3] = _labelsAbove[south - 1];
                                neighborLabels[4] = _labelsAbove[south];
                                neighborLabels[5] = _labelsAbove[south + 1];
                                neighborLabels[6] = _labelsHere[inOffset - 1];
                                nbNeighbors = 7;
                            }
                        }
                        else if (y == height - 1) {
                            final int north = inOffset - width;

                            if (x == 0) {
                                // e n n | e n n
                                // e n n | e x .
                                // e e e | e e e

                                neighborLabels[0] = _labelsAbove[north];
                                neighborLabels[1] = _labelsAbove[north + 1];
                                neighborLabels[2] = _labelsAbove[inOffset];
                                neighborLabels[3] = _labelsAbove[inOffset + 1];
                                neighborLabels[4] = _labelsHere[north];
                                neighborLabels[5] = _labelsHere[north + 1];
                                nbNeighbors = 6;
                            }
                            else if (x == width - 1) {
                                // n n e | n n e
                                // n n e | n x e
                                // e e e | e e e

                                neighborLabels[0] = _labelsAbove[north - 1];
                                neighborLabels[1] = _labelsAbove[north];
                                neighborLabels[2] = _labelsAbove[inOffset - 1];
                                neighborLabels[3] = _labelsAbove[inOffset];
                                neighborLabels[4] = _labelsHere[north - 1];
                                neighborLabels[5] = _labelsHere[north];
                                neighborLabels[6] = _labelsHere[inOffset - 1];
                                nbNeighbors = 7;
                            }
                            else {
                                // n n n | n n n
                                // n n n | n x .
                                // e e e | e e e

                                neighborLabels[0] = _labelsAbove[north - 1];
                                neighborLabels[1] = _labelsAbove[north];
                                neighborLabels[2] = _labelsAbove[north + 1];
                                neighborLabels[3] = _labelsAbove[inOffset - 1];
                                neighborLabels[4] = _labelsAbove[inOffset];
                                neighborLabels[5] = _labelsAbove[inOffset + 1];
                                neighborLabels[6] = _labelsHere[north - 1];
                                neighborLabels[7] = _labelsHere[north];
                                neighborLabels[8] = _labelsHere[north + 1];
                                neighborLabels[9] = _labelsHere[inOffset - 1];
                                nbNeighbors = 10;
                            }
                        }
                        else {
                            final int north = inOffset - width;
                            final int south = inOffset + width;

                            if (x == 0) {
                                // e n n | e n n
                                // e n n | e x .
                                // e n n | e . .

                                neighborLabels[0] = _labelsAbove[north];
                                neighborLabels[1] = _labelsAbove[north + 1];
                                neighborLabels[2] = _labelsAbove[inOffset];
                                neighborLabels[3] = _labelsAbove[inOffset + 1];
                                neighborLabels[4] = _labelsAbove[south];
                                neighborLabels[5] = _labelsAbove[south + 1];
                                neighborLabels[6] = _labelsHere[north];
                                neighborLabels[7] = _labelsHere[north + 1];
                                nbNeighbors = 8;
                            }
                            else if (x == width - 1) {
                                final int northwest = north - 1;
                                final int west = inOffset - 1;

                                // n n e | n n e
                                // n n e | n x e
                                // n n e | . . e

                                neighborLabels[0] = _labelsAbove[northwest];
                                neighborLabels[1] = _labelsAbove[north];
                                neighborLabels[2] = _labelsAbove[west];
                                neighborLabels[3] = _labelsAbove[inOffset];
                                neighborLabels[4] = _labelsAbove[south - 1];
                                neighborLabels[5] = _labelsAbove[south];
                                neighborLabels[6] = _labelsHere[northwest];
                                neighborLabels[7] = _labelsHere[north];
                                neighborLabels[8] = _labelsHere[west];
                                nbNeighbors = 9;
                            }
                            else {
                                final int northwest = north - 1;
                                final int west = inOffset - 1;
                                final int northeast = north + 1;
                                final int southwest = south - 1;
                                final int southeast = south + 1;

                                // n n n | n n n
                                // n n n | n x .
                                // n n n | . . .

                                neighborLabels[0] = _labelsAbove[northwest];
                                neighborLabels[1] = _labelsAbove[north];
                                neighborLabels[2] = _labelsAbove[northeast];
                                neighborLabels[3] = _labelsAbove[west];
                                neighborLabels[4] = _labelsAbove[inOffset];
                                neighborLabels[5] = _labelsAbove[inOffset + 1];
                                neighborLabels[6] = _labelsAbove[southwest];
                                neighborLabels[7] = _labelsAbove[south];
                                neighborLabels[8] = _labelsAbove[southeast];
                                neighborLabels[9] = _labelsHere[northwest];
                                neighborLabels[10] = _labelsHere[north];
                                neighborLabels[11] = _labelsHere[northeast];
                                neighborLabels[12] = _labelsHere[west];
                                nbNeighbors = 13;
                            }
                        }
                    }

                    // 2) the neighborhood is ready, move to the labeling step

                    // to avoid creating too many labels and fuse them later on,
                    // find the minimum non-zero label in the neighborhood
                    // and assign that minimum label right now

                    int currentLabel = Integer.MAX_VALUE;

                    for (int i = 0; i < nbNeighbors; i++) {
                        final int neighborLabel = neighborLabels[i];

                        // "zero" neighbors belong to the background...
                        if (neighborLabel == 0)
                            continue;

                        if (whatToExtract == ExtractionType.ALL_LABELS_VS_BACKGROUND
                                && ccs.get(neighborLabel).imageValue != currentImageValue)
                            continue;

                        // here, the neighbor label is valid
                        // => check if it is lower
                        if (neighborLabel < currentLabel) {
                            currentLabel = neighborLabel;
                        }
                    }

                    if (currentLabel == Integer.MAX_VALUE) {
                        // currentLabel didn't change
                        // => there is no lower neighbor
                        // => register a new connected component
                        highestKnownLabel++;
                        currentLabel = highestKnownLabel;
                        ccs.put(currentLabel, new ConnectedComponent(currentImageValue, currentLabel));
                    }
                    else {
                        // currentLabel has been modified
                        // -> browse the neighborhood again
                        // --> fuse high labels with low labels

                        final ConnectedComponent currentCC = ccs.get(currentLabel);
                        final int currentTargetLabel = currentCC.getTargetLabel();

                        for (int i = 0; i < nbNeighbors; i++) {
                            final int neighborLabel = neighborLabels[i];

                            if (neighborLabel == 0)
                                continue; // no object in this pixel

                            final ConnectedComponent neighborCC = ccs.get(neighborLabel);
                            final int neighborTargetLabel = neighborCC.getTargetLabel();

                            if (neighborTargetLabel == currentTargetLabel)
                                continue;

                            if (whatToExtract == ExtractionType.ALL_LABELS_VS_BACKGROUND
                                    && neighborCC.imageValue != currentImageValue)
                                continue;

                            // fuse the highest with the lowest
                            if (neighborTargetLabel > currentTargetLabel) {
                                ccs.get(neighborTargetLabel).targetComponent = ccs.get(currentTargetLabel);
                            }
                            else {
                                ccs.get(currentTargetLabel).targetComponent = ccs.get(neighborTargetLabel);
                            }
                        }
                    }

                    // -> store this label in the labeled image
                    if (currentLabel != 0)
                        _labelsHere[inOffset] = currentLabel;
                }
            }

            final IcyBufferedImage img = new IcyBufferedImage(width, height, _labelsHere);
            // pass to volatile if needed
            if (virtual)
                img.setVolatile(true);

            // store image in label sequence
            labelSequence.setImage(0, z, img);
            // store labels from previous slice
            _labelsAbove = _labelsHere;
        }

        // for debugging
        // Sequence preLabels = new Sequence();
        // preLabels.addImage(new IcyBufferedImage(width, height, new int[][] { _labels[0] }));
        // preLabels.getColorModel().setColorMap(0, new FireColorMap(), true);
        // addSequence(preLabels);

        // end of the first pass, all pixels have a label
        // (though might not be unique within a given component)

        if (Thread.currentThread().isInterrupted())
            return new ArrayList<>();

        // fusion strategy: fuse higher labels with lower ones
        // "highestKnownLabel" holds the highest known label
        // -> loop downwards from there to accumulate object size recursively

        int finalLabel = 0;

        for (int currentLabel = highestKnownLabel; currentLabel > 0; currentLabel--) {
            final ConnectedComponent currentCC = ccs.get(currentLabel);

            // if the target label is higher than or equal to the current label
            if (currentCC.targetLabel >= currentLabel) {
                // label has same labelValue and targetLabelValue
                // -> it cannot be fused to anything
                // -> this is a terminal label

                // -> assign its final labelValue (for the final image labeling pass)
                finalLabel++;
                currentCC.targetLabel = finalLabel;
            }
            else {
                // the current label should be fused to targetLabel
                currentCC.targetComponent = ccs.get(currentCC.targetLabel);
            }
        }

        // 3) second image pass: replace all labels by their final values

        finalPass:
        for (int z = 0; z < depth; z++) {
            // get labels for that slice
            final int[] _labelsHere = (int[]) labelSequence.getDataXY(0, z, 0);

            for (int j = 0, offset = 0; j < height; j++) {
                if (Thread.currentThread().isInterrupted())
                    break finalPass;

                for (int i = 0; i < width; i++, offset++) {
                    int targetLabel = _labelsHere[offset];

                    if (targetLabel == 0)
                        continue;

                    // retrieve the image value (for naming purposes)
                    final double imageValue = ccs.get(targetLabel).imageValue;

                    // if a fusion was indicated, retrieve the final label value
                    targetLabel = ccs.get(targetLabel).getTargetLabel();

                    // store the current pixel in the component
                    if (is3D) {
                        if (!roiMap.containsKey(targetLabel)) {
                            final ROI3DArea roi3D = new ROI3DArea();
                            roi3D.setName("Object #" + targetLabel + " (value: " + imageValue + ")");
                            roi3D.setT(t);
                            roi3D.setC(c);
                            roiMap.put(targetLabel, roi3D);
                        }

                        ((ROI3DArea) roiMap.get(targetLabel)).addPoint(i, j, z);
                    }
                    else {
                        if (!roiMap.containsKey(targetLabel)) {
                            final ROI2DArea roi2D = new ROI2DArea();
                            roi2D.setName("Object #" + targetLabel + " (value: " + imageValue + ")");
                            roi2D.setZ(0);
                            roi2D.setT(t);
                            roi2D.setC(c);
                            roiMap.put(targetLabel, roi2D);
                        }

                        ((ROI2DArea) roiMap.get(targetLabel)).addPoint(i, j);
                    }
                }
            }
        }

        return new ArrayList<>(roiMap.values());
    }
}
