package com.example.lotterysystem.service;

import org.springframework.web.multipart.MultipartFile;

public interface PictureService {


    /**
     * 保存图片
     *
     * @param multipartFile： 上传文件的工具类
     * @return  索引：上传后的文件名（唯一）
     */
    String savePicture(MultipartFile multipartFile);


}
