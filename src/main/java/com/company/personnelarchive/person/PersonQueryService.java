package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;

@Service
/**
 * 人员档案只读用例：列表、详情和家庭成员模糊查询都限定为当前有效快照。
 * 基础详情只返回前四后三脱敏值；完整身份证与手机号必须通过单字段敏感读取显式获取。
 */
public class PersonQueryService {
    private final PersonReadMapper mapper;
    private final ArchiveProperties properties;
    private final AuditService audit;
    private final IdentityProtection identityProtection;

    public PersonQueryService(PersonReadMapper mapper, ArchiveProperties properties,
                              AuditService audit, IdentityProtection identityProtection) {
        this.mapper = mapper; this.properties = properties; this.audit = audit;
        this.identityProtection = identityProtection;
    }

    public PersonDtos.Detail detail(long personId, long operatorId) {
        // requireActive 集中落实普通读取不暴露历史软删除快照的规则。
        PersonReadMapper.PersonRow person = requireActive(personId);
        PersonReadMapper.EmploymentRow employment = mapper.findEmployment(properties.tenantId(), personId);
        List<PersonReadMapper.EducationRow> education = mapper.findEducation(properties.tenantId(), personId);
        PersonReadMapper.PhotoMetadataRow photo = mapper.findPhotoMetadata(properties.tenantId(), personId);
        var family = mapper.findFamily(properties.tenantId(), personId).stream()
            .map(member -> new PersonDtos.FamilyMember(value(member.memberName()), value(member.relationshipName()),
                value(member.birthDate()), value(member.ageText()),
                value(member.politicalStatus()), value(member.organizationAndPosition())))
            .toList();
        PersonDtos.Detail detail = new PersonDtos.Detail(
            String.valueOf(person.id()), value(person.employeeNo()), value(person.personName()),
            maskIdentity(person.idCardCiphertext()),
            personal(person), employment(employment), education(education),
            education.stream().filter(row -> "MAIN".equals(row.educationType())).map(PersonQueryService::orderedText).toList(),
            mapper.findWork(properties.tenantId(), personId).stream().map(PersonQueryService::orderedText).toList(),
            mapper.findRewards(properties.tenantId(), personId).stream().map(PersonQueryService::orderedText).toList(),
            family, new PersonDtos.DeclarationInfo(value(person.declarationSigner()), value(person.declarationDate())),
            photo == null ? null : new PersonDtos.PhotoMetadata(photo.mimeType(), photo.originalFilename(),
                photo.byteSize(), photo.widthPixels(), photo.heightPixels()),
            new PersonDtos.ArchiveMetadata(value(person.templateVersion()), person.snapshotVersion(), person.updatedAt().toString()));
        audit.record(operatorId, "VIEW", "PERSON", personId, "SUCCESS", true);
        return detail;
    }

    public PersonDtos.SensitiveValue sensitiveValue(long personId, PersonDtos.SensitiveField field,
                                                     long operatorId) {
        PersonReadMapper.PersonRow person = requireActive(personId);
        String value = switch (field) {
            case IDENTITY_CARD -> person.idCardCiphertext() == null
                ? "" : identityProtection.decrypt(person.idCardCiphertext());
            case PHONE_NUMBER -> value(person.phoneNumber());
        };
        audit.recordSensitiveView(operatorId, personId, field.name());
        return new PersonDtos.SensitiveValue(value);
    }

    public PhotoContent photo(long personId, long operatorId) {
        requireActive(personId);
        PersonReadMapper.PhotoRow photo = mapper.findPhoto(properties.tenantId(), personId);
        if (photo == null) throw new BusinessException(ErrorCode.NOT_FOUND, "该档案没有照片");
        audit.record(operatorId, "VIEW", "PERSON", personId, "SUCCESS", true);
        return new PhotoContent(photo.photoData(), photo.mimeType(), photo.originalFilename());
    }

    public PersonDtos.PersonListPage list(String name, String employeeNo, String department,
                                          int page, int size, long operatorId) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不正确");
        }
        // 先规范化再转义 LIKE 元字符，确保用户输入的 % 和 _ 按普通字符匹配。
        String escapedName = escapeLike(value(NameNormalizer.normalize(name)));
        String escapedEmployeeNo = escapeLike(normalizeFilter(employeeNo));
        String escapedDepartment = escapeLike(normalizeFilter(department));
        long total = mapper.countActivePersons(properties.tenantId(), escapedName, escapedEmployeeNo, escapedDepartment);
        var content = mapper.findActivePersons(properties.tenantId(), escapedName, escapedEmployeeNo, escapedDepartment,
                (long) page * size, size).stream()
            .map(row -> new PersonDtos.PersonListItem(String.valueOf(row.id()), value(row.employeeNo()), value(row.personName()),
                value(row.organizationName()), value(row.departmentName()), value(row.positionName()),
                value(row.professionalTitleOrSkill()), row.updatedAt().toString()))
            .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        audit.record(operatorId, "VIEW", "PERSON_LIST", null, "SUCCESS", true);
        return new PersonDtos.PersonListPage(content, page, size, total, totalPages);
    }

    public PersonDtos.FamilySearchPage searchFamily(String name, int page, int size) {
        String normalized = NameNormalizer.normalize(name);
        if (normalized == null || normalized.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "家庭成员姓名不能为空");
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数不正确");
        // contains 查询可能返回同一人员的多个匹配成员，结果不能按 personId 去重。
        String escaped = escapeLike(normalized);
        long total = mapper.countFamily(properties.tenantId(), escaped);
        var rows = mapper.searchFamily(properties.tenantId(), escaped, page * size, size);
        var results = rows.stream().map(row -> new PersonDtos.FamilySearchResult(String.valueOf(row.personId()), value(row.personName()),
            value(row.organizationName()), value(row.departmentName()), value(row.positionName()), value(row.professionalTitleOrSkill()),
            row.memberName(), value(row.relationshipName()))).toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new PersonDtos.FamilySearchPage(results, page, size, total, totalPages);
    }

    PersonReadMapper.PersonRow requireActive(long personId) {
        PersonReadMapper.PersonRow row = mapper.findActive(properties.tenantId(), personId);
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return row;
    }

    private static String normalizeFilter(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
    }

    private String maskIdentity(byte[] ciphertext) {
        return ciphertext == null ? "" : SensitiveValueMasker.mask(identityProtection.decrypt(ciphertext));
    }

    private static String escapeLike(String input) {
        return input.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static PersonDtos.PersonalInfo personal(PersonReadMapper.PersonRow person) {
        return new PersonDtos.PersonalInfo(value(person.gender()), value(person.birthDate()), value(person.nation()),
            value(person.nativePlace()), value(person.birthPlace()), value(person.politicalStatus()),
            value(person.politicalJoinDate()), value(person.maritalStatus()), value(person.healthStatus()),
            value(person.mailingAddress()), value(person.postalCode()),
            SensitiveValueMasker.mask(person.phoneNumber()));
    }

    private static PersonDtos.EmploymentDetails employment(PersonReadMapper.EmploymentRow row) {
        if (row == null) return new PersonDtos.EmploymentDetails("", "", "", "", "", "", "");
        return new PersonDtos.EmploymentDetails(value(row.organizationName()), value(row.departmentName()),
            value(row.workStartDate()), value(row.dispatchedToBiddingCompanyDate()),
            value(row.positionName()), value(row.professionalTitleOrSkill()),
            value(row.titleOrSkillAcquiredDate()));
    }

    private static PersonDtos.EducationDetails education(List<PersonReadMapper.EducationRow> rows) {
        return new PersonDtos.EducationDetails(summary(rows, "FULL_TIME"), summary(rows, "IN_SERVICE"));
    }

    private static PersonDtos.EducationSummary summary(List<PersonReadMapper.EducationRow> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.educationType())).findFirst()
            .map(row -> new PersonDtos.EducationSummary(value(row.institutionName()), value(row.departmentAndMajor()),
                value(row.educationLevel()), value(row.degreeName())))
            .orElseGet(() -> new PersonDtos.EducationSummary("", "", "", ""));
    }

    private static PersonDtos.OrderedText orderedText(PersonReadMapper.EducationRow row) {
        return orderedText(row.sortOrder(), row.rawText());
    }

    private static PersonDtos.OrderedText orderedText(PersonReadMapper.WorkRow row) {
        return orderedText(row.sortOrder(), row.rawText());
    }

    private static PersonDtos.OrderedText orderedText(PersonReadMapper.RewardRow row) {
        return orderedText(row.sortOrder(), row.rawText());
    }

    private static PersonDtos.OrderedText orderedText(int sortOrder, String rawText) {
        String[] columns = value(rawText).split("\\t", 2);
        return new PersonDtos.OrderedText(sortOrder, columns[0], columns.length == 1 ? "" : columns[1]);
    }

    private static String value(String value) { return value == null ? "" : value; }

    public record PhotoContent(byte[] bytes, String mimeType, String filename) {}
}
