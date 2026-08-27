package com.hjmicro.utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;


@Component
@Slf4j
public class MinIoUtil {

    @Autowired
    private MinioClient client;

    /**
     * 创建桶
     *
     * @param bucketName 桶名称
     */
    @SneakyThrows
    public void createBucket(String bucketName) {
        boolean found =
                client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!found) {
            client.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucketName)
                            .build());
        }
    }

    /**
     * 删除桶
     *
     * @param bucketName 桶名称
     */
    @SneakyThrows
    public void removeBucket(String bucketName) {
        client.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 获取文件信息
     *
     * @param bucketName 桶名称
     * @param objectName 文件名称
     * @return
     */
    @SneakyThrows
    public StatObjectResponse getObjectInfo(String bucketName, String objectName) {
        return client.statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
    }

    /**
     * 上传文件
     *
     * @param bucketName 桶名称
     * @param objectName 文件名
     * @param stream     流
     * @param fileSize   文件大小
     * @param type       文件类型
     * @throws Exception
     */
    public void putObject(String bucketName, String objectName, InputStream stream
            , Long fileSize, String type) throws Exception {
        if (StringUtils.isBlank(type)){
            client.putObject(
                    PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                                    stream, fileSize, -1)
                            .build());
        }else{
            client.putObject(
                    PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                                    stream, fileSize, -1)
                            .contentType(type)
                            .build());
        }
    }


    /**
     * 判断文件夹是否存在
     *
     * @param bucketName 桶名称
     * @param prefix     文件夹名字
     * @return
     */
    @SneakyThrows
    public Boolean folderExists(String bucketName, String prefix) {
        Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder().bucket(bucketName)
                .prefix(prefix).recursive(false).build());
        for (Result<Item> result:results){
            Item item = result.get();
            if (item.isDir()){
                return true;
            }
        }
        return false;
    }

    /**
     * 创建文件夹
     * @param bucketName 桶名称
     * @param path 路径
     */
    @SneakyThrows
    public void createFolder(String bucketName,String path) {
        client.putObject(PutObjectArgs.builder().bucket(bucketName).object(path)
                .stream(new ByteArrayInputStream(new byte[]{}),0,-1).build());
    }

    /**
     * 获取文件在minio在服务器上的外链
     * @param bucketName 桶名称
     * @param objectName 文件名
     * @return
     */
    @SneakyThrows
    public String getObjectUrl(String bucketName,String objectName){
        return client.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
    }

}
