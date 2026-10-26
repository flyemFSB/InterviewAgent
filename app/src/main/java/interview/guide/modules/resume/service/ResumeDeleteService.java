package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 简历删除服务
 * 处理简历删除的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {
    
    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final FileStorageService storageService;
    private final InterviewSessionCache sessionCache;
    
    /**
     * 删除简历
     * 
     * @param id 简历ID
     * @throws interview.guide.common.exception.BusinessException 如果简历不存在
     */
    public void deleteResume(Long id) {
        log.info("收到删除简历请求: id={}", id);
        
        // 获取简历信息（用于删除存储文件）
        ResumeEntity resume = persistenceService.findById(id)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.RESUME_NOT_FOUND));

        // 先记下待删会话 id：会话缓存是 per-session 键，删库之后就查不到关联了
        List<String> sessionIds = interviewPersistenceService.findByResumeId(id).stream()
            .map(InterviewSessionEntity::getSessionId)
            .toList();

        // 1. 删除存储的文件（FileStorageService 已内置存在性检查）
        try {
            storageService.deleteResume(resume.getStorageKey());
        } catch (Exception e) {
            log.warn("删除存储文件失败，继续删除数据库记录: {}", e.getMessage());
        }
        
        // 2. 删除面试会话（会自动删除面试答案）
        interviewPersistenceService.deleteSessionsByResumeId(id);
        
        // 3. 删除数据库记录（包括分析记录）
        persistenceService.deleteResume(id);

        // 4. 清理会话缓存。不清理的话已删会话在 TTL（24h）内仍可读，
        //    且报告页会在缓存命中后对已删会话发起真实 LLM 评估。
        sessionIds.forEach(sessionCache::deleteSession);

        log.info("简历删除完成: id={}, 清理会话缓存 {} 个", id, sessionIds.size());
    }
}

