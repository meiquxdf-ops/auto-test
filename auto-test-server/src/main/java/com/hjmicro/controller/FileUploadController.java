package com.hjmicro.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.fluent.mapper.TaskMapper;
import com.hjmicro.fluent.wrapper.TaskQuery;
import com.hjmicro.utils.MinIoUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@CrossOrigin(originPatterns = "*")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private MinIoUtil minIoUtil;


    @Value("${minio.domain}")
    private String minioDomain;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    @Qualifier("uploadExecutor")
    private Executor uploadExecutor;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("请选择一个文件上传。");
        }
        String requestId = httpServletRequest.getParameter("requestId");
        String caseNo = httpServletRequest.getParameter("caseNo");
        String path = httpServletRequest.getParameter("path");

        if (StringUtils.isBlank(requestId) || StringUtils.isBlank(caseNo)) {
            return ResponseEntity.badRequest().body("requestId 或 caseNo 不能为空。");
        }

        String originalFileName = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        long fileSize = file.getSize();

        Path tempFile;
        try {
            tempFile = Files.createTempFile("upload-", ".tmp");
            file.transferTo(tempFile);
        } catch (IOException e) {
            logger.error("文件落盘失败 requestId={}, caseNo={}", requestId, caseNo, e);
            return ResponseEntity.status(500).body("文件上传失败：无法保存临时文件。");
        }

        try {
            CompletableFuture.runAsync(
                            () -> handleUpload(tempFile, requestId, caseNo, path, originalFileName, fileSize),
                            uploadExecutor)
                    .exceptionally(ex -> {
                        logger.error("文件上传异步处理失败 requestId={}, caseNo={}", requestId, caseNo, ex);
                        cleanupTempFile(tempFile);
                        return null;
                    });
        } catch (RejectedExecutionException ex) {
            cleanupTempFile(tempFile);
            return ResponseEntity.status(429).body("上传队列已满，请稍后重试。");
        }

        return ResponseEntity.accepted().body("文件正在上传，请稍后查看结果。");
    }

    private void handleUpload(Path tempFile, String requestId, String caseNo, String path,
                              String originalFileName, long fileSize) {
        try (InputStream stream = Files.newInputStream(tempFile)) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd/");
            String datePath = sdf.format(new Date());
            String filePath = datePath + requestId + "/" + caseNo + "/";

            String fileExtension = originalFileName.contains(".")
                    ? originalFileName.substring(originalFileName.lastIndexOf(".") + 1)
                    : "";
            String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            String uniqueFileName = originalFileName + "(" + uuid + ")" + (fileExtension.isEmpty() ? "" : "." + fileExtension);

            String fullUploadPath = filePath + uniqueFileName;
            minIoUtil.putObject("autotest", fullUploadPath, stream, fileSize, null);

            String imgUrl = minioDomain + "/autotest/" + fullUploadPath;

            TaskEntity taskEntity = taskMapper.findOne(new TaskQuery().where.requestId().eq(requestId).name().eq(caseNo).end());
            if (taskEntity == null) {
                logger.warn("未找到任务记录 requestId={}, caseNo={}", requestId, caseNo);
                return;
            }
            String annex = taskEntity.getAnnex();
            JSONArray jsonArray = new JSONArray();
            if (StringUtils.isNotBlank(annex)) {
                jsonArray = JSONObject.parseArray(annex);
            }
            JSONObject obj = new JSONObject();
            obj.put("fileName", originalFileName);
            obj.put("url", imgUrl);
            obj.put("fileSize", fileSize);
            obj.put("filePath", path);
            jsonArray.add(obj);
            taskEntity.setAnnex(jsonArray.toString());
            taskMapper.updateById(taskEntity);
        } catch (Exception e) {
            logger.error("文件上传处理失败 requestId={}, caseNo={}", requestId, caseNo, e);
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            logger.warn("清理临时文件失败 path={}", tempFile, e);
        }
    }

}
