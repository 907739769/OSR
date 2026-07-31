package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖 {@link MediaServerClientFactory} 的分发顺序：
 * 应优先从 Map 中查找已注册实现，JELLYFIN 只是在没有独立实现时的兜底。
 *
 * @author Jack
 */
class MediaServerClientFactoryTest {

    private EmbyClient embyClient;

    @BeforeEach
    void setUp() {
        embyClient = new EmbyClient(new OkHttpClient());
    }

    private PtMediaServerPlus config(String type) {
        PtMediaServerPlus c = new PtMediaServerPlus();
        c.setId(1);
        c.setName("server");
        c.setType(type);
        return c;
    }

    @Test
    void type为EMBY_返回EmbyClient实例() {
        MediaServerClientFactory factory = new MediaServerClientFactory(List.of(embyClient), embyClient);

        IMediaServerClient result = factory.get(config("EMBY"));

        assertSame(embyClient, result);
    }

    @Test
    void type为JELLYFIN且无独立实现_兜底返回EmbyClient实例() {
        MediaServerClientFactory factory = new MediaServerClientFactory(List.of(embyClient), embyClient);

        IMediaServerClient result = factory.get(config("JELLYFIN"));

        assertSame(embyClient, result);
    }

    @Test
    void type为JELLYFIN且存在独立实现_返回该实现而非EmbyClient() {
        FakeJellyfinClient jellyfinClient = new FakeJellyfinClient();
        MediaServerClientFactory factory = new MediaServerClientFactory(
                List.of(embyClient, jellyfinClient), embyClient);

        IMediaServerClient result = factory.get(config("JELLYFIN"));

        assertSame(jellyfinClient, result);
    }

    @Test
    void type为未知值_抛出IllegalArgumentException() {
        MediaServerClientFactory factory = new MediaServerClientFactory(List.of(embyClient), embyClient);

        assertThrows(IllegalArgumentException.class, () -> factory.get(config("PLEX")));
    }

    /**
     * 假的独立 JellyfinClient 实现，仅用于验证工厂的分发顺序，不关心方法体行为。
     */
    private static class FakeJellyfinClient implements IMediaServerClient {

        @Override
        public String type() {
            return "JELLYFIN";
        }

        @Override
        public boolean testConnection(PtMediaServerPlus config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasMovie(PtMediaServerPlus config, String tmdbId) throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
