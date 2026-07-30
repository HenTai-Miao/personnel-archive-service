package com.company.personnelarchive.configuration;

import com.company.personnelarchive.archiveimport.ImportMapper;
import com.company.personnelarchive.audit.AuditMapper;
import com.company.personnelarchive.auth.AuthMapper;
import com.company.personnelarchive.person.PersonMapper;
import com.company.personnelarchive.person.PersonReadMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MapperSqlPortabilityTest {
    private static final List<Class<?>> MAPPERS = List.of(
        AuthMapper.class,
        PersonMapper.class,
        PersonReadMapper.class,
        ImportMapper.class,
        AuditMapper.class);

    @Test
    void sharedMapperSqlAvoidsMysqlOnlyFunctionsAndBackslashEscaping() {
        assertThat(allMapperSql())
            .doesNotContain("LAST_INSERT_ID(")
            .doesNotContain("DATE_ADD(")
            .doesNotContain("ORDER BY FIELD(")
            .doesNotContain("ESCAPE '\\\\'")
            .contains("ESCAPE '!'")
            .contains("ORDER BY CASE education_type");
    }

    private static String allMapperSql() {
        return MAPPERS.stream()
            .flatMap(mapper -> Stream.of(mapper.getDeclaredMethods()))
            .flatMap(MapperSqlPortabilityTest::sqlFragments)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    private static Stream<String> sqlFragments(Method method) {
        Stream.Builder<String> fragments = Stream.builder();
        Select select = method.getAnnotation(Select.class);
        Insert insert = method.getAnnotation(Insert.class);
        Update update = method.getAnnotation(Update.class);
        if (select != null) Stream.of(select.value()).forEach(fragments::add);
        if (insert != null) Stream.of(insert.value()).forEach(fragments::add);
        if (update != null) Stream.of(update.value()).forEach(fragments::add);
        return fragments.build();
    }
}
