package com.njupt.wirelesslabagent.controller;

import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import com.njupt.wirelesslabagent.service.KnowledgeDocumentService;
import com.njupt.wirelesslabagent.service.KnowledgeMetadataResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final KnowledgeDocumentService documentService;
    private final KnowledgeMetadataResolver metadataResolver;
    private final Path uploadDirectory;

    public KnowledgeController(KnowledgeDocumentService documentService,
                               KnowledgeMetadataResolver metadataResolver,
                               @Value("${knowledge.upload-dir:#{systemProperties['user.dir'] + '/tmp/knowledge'}}")
                               String uploadDirectory) {
        this.documentService = documentService;
        this.metadataResolver = metadataResolver;
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @PostMapping("/upload")
    public BaseResponse<?> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(defaultValue = "自动识别") String category,
                                  @RequestParam(defaultValue = "自动识别") String deviceType,
                                  @RequestParam(defaultValue = "自动识别") String documentType,
                                  @RequestParam(defaultValue = "自动识别") String chapter,
                                  HttpServletRequest request) {
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null || !isSupported(originalName)) {
            return ResuitUtils.error(40000, "仅支持 PDF、DOC、DOCX、Markdown 和 TXT 文档");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResuitUtils.error(40000, "文件大小不能超过 20MB");
        }

        try {
            Files.createDirectories(uploadDirectory);
            String extension = originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            Path savedFile = uploadDirectory.resolve(UUID.randomUUID() + extension).normalize();
            if (!savedFile.startsWith(uploadDirectory)) {
                return ResuitUtils.error(40000, "非法文件路径");
            }
            file.transferTo(savedFile);
            String userId = (String) request.getAttribute("currentUser");
            String taskId = documentService.submit(
                    savedFile, originalName, userId, category, deviceType, documentType, chapter);
            return ResuitUtils.success(Map.of("taskId", taskId, "status", "processing"));
        } catch (Exception exception) {
            log.error("知识文档上传失败", exception);
            return ResuitUtils.error(50000, "文件保存或任务投递失败");
        }
    }

    @GetMapping("/result/{taskId}")
    public BaseResponse<?> result(@PathVariable String taskId) {
        var result = documentService.getResult(taskId);
        if (result == null) {
            return ResuitUtils.error(40400, "任务不存在或已过期");
        }
        return ResuitUtils.success(result);
    }

    @GetMapping("/categories")
    public BaseResponse<?> categories() {
        return ResuitUtils.success(metadataResolver.supportedCategories());
    }

    private boolean isSupported(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".md") || lower.endsWith(".txt");
    }
}
