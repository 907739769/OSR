package com.osr.openliststrm.rename;

import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 简单规则匹配器，基于 MediaInfo 提供的字段（tmdb 扩展字段可能包括 genreIds, originalLanguage, originCountries）
 */
@Getter
@Slf4j
public class CategoryRule {
    private final String name;
    private final Set<String> genreIds = new HashSet<>();
    private final Set<String> originalLanguages = new HashSet<>();
    private final Set<String> originCountries = new HashSet<>();

    public CategoryRule(String name) {
        this.name = name;
    }

    public CategoryRule withGenreIds(String... ids) {
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty()) genreIds.add(id.trim());
            }
        }
        return this;
    }

    public CategoryRule withOriginalLanguage(String... langs) {
        if (langs != null) {
            for (String l : langs) {
                if (l != null && !l.isEmpty()) originalLanguages.add(l.trim().toLowerCase());
            }
        }
        return this;
    }

    public CategoryRule withOriginCountry(String... countries) {
        if (countries != null) {
            for (String c : countries) {
                if (c != null && !c.isEmpty()) originCountries.add(c.trim().toUpperCase());
            }
        }
        return this;
    }

    public boolean matches(MediaInfo info) {
        // 1. 检查 genreIds
        if (!genreIds.isEmpty()) {
            List<String> infoGenres = getGenreIdsFromInfo(info);
            if (infoGenres.isEmpty() || Collections.disjoint(infoGenres, genreIds)) {
                return false;
            }
        }

        // 2. 检查 originalLanguages
        if (!originalLanguages.isEmpty()) {
            String lang = getOriginalLanguageFromInfo(info);
            if (lang == null || !originalLanguages.contains(lang.toLowerCase())) {
                return false;
            }
        }

        // 3. 检查 originCountries
        if (!originCountries.isEmpty()) {
            List<String> countries = getOriginCountriesFromInfo(info);
            if (countries == null || countries.isEmpty()) return false;

            boolean matchedCountry = countries.stream()
                    .anyMatch(c -> originCountries.contains(c.toUpperCase()));
            if (!matchedCountry) return false;
        }

        // 4. 如果没有任何条件，或者全部条件都满足
        return true;
    }

    // helper extractors -- read extended TMDb fields from MediaInfo, with metadata map fallback
    private List<String> getGenreIdsFromInfo(MediaInfo info) {
        List<String> genres = info.getGenreIds();
        if (genres != null && !genres.isEmpty()) return genres;
        // fallback to metadata map
        Map<String, Object> meta = info.getMetadata();
        if (meta != null) {
            Object g = meta.get("genre_ids");
            if (g instanceof List) {
                List<String> out = new ArrayList<>();
                for (Object o : (List<?>) g) out.add(String.valueOf(o));
                return out;
            }
        }
        return Collections.emptyList();
    }

    private String getOriginalLanguageFromInfo(MediaInfo info) {
        String lang = info.getOriginalLanguage();
        if (lang != null) return lang;
        // fallback to metadata map
        Map<String, Object> meta = info.getMetadata();
        if (meta != null) {
            Object ol = meta.get("original_language");
            if (ol != null) return String.valueOf(ol);
        }
        return null;
    }

    private List<String> getOriginCountriesFromInfo(MediaInfo info) {
        List<String> countries = info.getOriginCountries();
        if (countries != null && !countries.isEmpty()) return countries;
        // fallback to metadata map
        Map<String, Object> meta = info.getMetadata();
        if (meta != null) {
            Object oc = meta.get("origin_country");
            if (oc instanceof List) {
                List<String> out = new ArrayList<>();
                for (Object o : (List<?>) oc) out.add(String.valueOf(o));
                return out;
            }
            if (oc instanceof String) {
                return Arrays.asList(((String) oc).split(","));
            }
        }
        return Collections.emptyList();
    }
}
