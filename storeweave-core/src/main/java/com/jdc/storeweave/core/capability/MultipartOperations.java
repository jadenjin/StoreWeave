package com.jdc.storeweave.core.capability;

import com.jdc.storeweave.core.model.ObjectContent;
import com.jdc.storeweave.core.model.StorageObject;

import java.util.List;

public interface MultipartOperations extends StorageCapability {

    MultipartUpload initiate(StorageObject object);

    UploadedPart uploadPart(MultipartUpload upload, int partNumber, ObjectContent content);

    void complete(MultipartUpload upload, List<UploadedPart> parts);

    void abort(MultipartUpload upload);

    List<UploadedPart> listParts(MultipartUpload upload);
}
