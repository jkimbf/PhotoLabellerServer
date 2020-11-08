package com.github.darrmirr;

import com.github.darrmirr.models.Dl4jModel;
import com.github.darrmirr.models.InceptionResNetV1;
import com.github.darrmirr.models.mtcnn.Mtcnn;
import com.github.darrmirr.utils.FaceFeatures;
import com.github.darrmirr.utils.ImageFace;
import com.github.darrmirr.utils.ImageUtils;
import com.github.darrmirr.utils.Nd4jUtils;
import org.datavec.image.loader.NativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Getting Face Features
 *
 * Process consist two stages:
 * 1. Detect faces in image using MTCNN
 * 2. Extract face features from detected face
 *
 */

@Component
public class FaceDetector {
    private static final Logger logger = LoggerFactory.getLogger(FaceDetector.class);
    private NativeImageLoader loader = new NativeImageLoader();
    private Mtcnn mtcnn;
    private ImageUtils imageUtils;
    private Dl4jModel model;
    private ComputationGraph faceFeatureExtracter;
    private Nd4jUtils nd4jUtils;

    @Autowired
    public FaceDetector(Mtcnn mtcnn, InceptionResNetV1 model, ImageUtils imageUtils, Nd4jUtils nd4jUtils) {
        this.mtcnn = mtcnn;
        this.imageUtils = imageUtils;
        this.model = model;
        this.nd4jUtils = nd4jUtils;
    }

    @PostConstruct
    public void init() throws IOException {
        faceFeatureExtracter = model.getGraph();
    }

    /**
     * Detect faces in image
     *
     * @param image image file
     * @return array of detected images
     * @throws IOException exception while file is read
     */

    public List<ImageFace> detectFaces(File image) throws IOException {
        INDArray imageMatrix = loader.asMatrix(image);
        return mtcnn
                .detectFaces(imageMatrix)
                .stream()
                .map(boundBox -> {
                    INDArray imageFace = nd4jUtils.crop(boundBox, imageMatrix);
                    return new ImageFace(imageFace, boundBox);
                })
                .collect(toList());
    }

    /**
     * Extract features for each face in array
     *
     * @param faces INDArray represent faces on image (image size depend on model)
     * @return list of face feature vectors
     */
    public List<ImageFace> extractFeatures(List<ImageFace> faces) {
        logger.info("Extract features from faces : {}", faces.size());
        faces.stream().parallel().forEach(imageFace -> {
            INDArray resizedFace = Nd4jUtils.imresample(imageFace.get(), model.inputHeight(), model.inputWidth());
            INDArray output = faceFeatureExtracter.output(resizedFace)[1];
            imageFace.setFeatureVector(output);
        });
        return faces;
    }

    /**
     * Method combine detect faces and extract features for each face in array
     *
     * @param image image file
     * @return list of face feature vectors
     * @throws IOException exception while file is read
     */

    public FaceFeatures getFaceFeatures(File image) throws IOException {
        logger.info("start : {}", image.getName());
        List<ImageFace> detectedFaces = detectFaces(image);

        if(detectedFaces == null || detectedFaces.isEmpty()) {
            logger.warn("no face detected in image file : {}", image);
            return new FaceFeatures(image, Collections.emptyList());
        }

        if(logger.isDebugEnabled()) {
            logger.debug("save detected face image");
            for (int i = 0; i < detectedFaces.size(); i++) {
                imageUtils.toFile(detectedFaces.get(i).getImageFace(), "jpg", i + "_" + image.getName() );
            }
        }
        List<ImageFace> imageFaces = extractFeatures(detectedFaces);
        logger.info("end : {}", image.getName());
        return new FaceFeatures(image, imageFaces);
    }
}