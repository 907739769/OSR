package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.MediaIdentityBindingPlus;
import com.osr.openliststrm.mybatisplus.mapper.MediaIdentityBindingPlusMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 网络取数完成后再开启短事务；文件绑定与完整快照标记同时替换。 */
@Service
public class MediaIdentityBindingStore {
    private final MediaIdentityBindingPlusMapper mapper;

    public MediaIdentityBindingStore(MediaIdentityBindingPlusMapper mapper) { this.mapper = mapper; }

    @Transactional
    public void replaceDownload(Integer downloadId, List<MediaIdentityBindingPlus> files,
                                MediaIdentityBindingPlus snapshot) {
        mapper.delete(new QueryWrapper<MediaIdentityBindingPlus>().eq("download_id", downloadId));
        for (MediaIdentityBindingPlus file : files) mapper.upsert(file);
        mapper.upsert(snapshot);
    }
}
