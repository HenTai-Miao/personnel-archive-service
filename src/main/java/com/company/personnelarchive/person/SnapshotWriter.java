package com.company.personnelarchive.person;

import com.company.personnelarchive.archiveimport.ArchiveDocument;
import com.company.personnelarchive.archiveimport.ExcelTemplateV1;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;

@Service
/**
 * 将已解析的工作簿写成不可变人员快照及其所有子记录。
 * 调用方负责事务；本类保证同一根下的教育、履历、奖惩、家庭成员和照片完整写入。
 */
public class SnapshotWriter {
    private final PersonMapper mapper;
    private final IdentityProtection protection;
    private final ArchiveProperties properties;
    private final Clock clock;

    public SnapshotWriter(PersonMapper mapper, IdentityProtection protection, ArchiveProperties properties, Clock clock) {
        this.mapper = mapper;
        this.protection = protection;
        this.properties = properties;
        this.clock = clock;
    }

    public PreparedArchive prepare(ArchiveDocument document) {
        // 字段允许留空；只对实际填写的身份证执行规范化和格式校验。
        document.validateCapacity();
        String employeeNo = cleanOptional(document.employeeNo());
        String name = cleanOptional(document.name());
        String identityInput = cleanOptional(document.identityCard());
        String identity = identityInput.isEmpty() ? null : IdentityCard.normalizeAndValidate(identityInput);
        LocalDate today = LocalDate.now(clock);
        if (identity != null) {
            LocalDate identityBirthDate = LocalDate.parse(identity.substring(6, 14), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if (identityBirthDate.isAfter(today)) {
                throw new BusinessException(ErrorCode.INVALID_IDENTITY_CARD, "身份证出生日期不能晚于当前日期");
            }
        }
        if (document.birthDate() != null && document.birthDate().isAfter(today)) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "人员出生日期不能晚于当前日期");
        }
        if (document.familyMembers() != null && document.familyMembers().stream()
            .anyMatch(member -> member.birthDate() != null && member.birthDate().isAfter(today))) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "家庭成员出生日期不能晚于当前日期");
        }
        // 只有填写身份证时才生成 HMAC；空身份证必须保持 NULL，避免所有空档案互相冲突。
        return new PreparedArchive(document, employeeNo, name, NameNormalizer.normalize(name),
            identity, identity == null ? null : protection.hmac(identity));
    }

    public long insert(PreparedArchive prepared, long operatorId, long snapshotVersion, Long previousPersonId) {
        ArchiveDocument d = prepared.document();
        PersonMapper.PersonInsert row = new PersonMapper.PersonInsert();
        row.tenantId = properties.tenantId();
        row.employeeNo = prepared.employeeNo();
        row.personName = prepared.name();
        row.normalizedName = prepared.normalizedName();
        // 未填写身份证时三列同时为 NULL；填写后才保存密文、检索 HMAC 和展示尾号。
        if (prepared.identityCard() != null) {
            row.idCardCiphertext = protection.encrypt(prepared.identityCard());
            row.idCardHmac = prepared.identityHmac();
            row.idCardLastFour = IdentityCard.lastFour(prepared.identityCard());
        }
        row.gender = blankToNull(d.gender()); row.birthDate = blankToNull(d.birthDate().text());
        row.nation = blankToNull(d.ethnicity());
        row.nativePlace = blankToNull(d.nativePlace()); row.birthPlace = blankToNull(d.birthPlace());
        row.politicalStatus = blankToNull(d.politicalStatus());
        row.politicalJoinDate = blankToNull(d.politicalJoinDate().text());
        row.maritalStatus = blankToNull(d.maritalStatus()); row.healthStatus = blankToNull(d.healthStatus());
        row.mailingAddress = blankToNull(d.address()); row.postalCode = blankToNull(d.postalCode());
        row.phoneNumber = blankToNull(d.phone()); row.declarationSigner = blankToNull(d.declarationSigner());
        row.declarationDate = blankToNull(d.declarationDate().text()); row.templateVersion = ExcelTemplateV1.VERSION;
        row.snapshotVersion = snapshotVersion; row.previousPersonId = previousPersonId; row.operatorId = operatorId;
        mapper.insertPerson(row);
        long personId = row.id;
        writeChildren(personId, d);
        return personId;
    }

    private void writeChildren(long personId, ArchiveDocument d) {
        var employment = d.employment();
        if (employment != null) {
            OrganizationDepartment organization = splitOrganizationDepartment(employment.organizationDepartment());
            mapper.insertEmployment(properties.tenantId(), personId,
                organization.organization(), organization.department(), blankToNull(employment.workStartDate().text()),
                blankToNull(employment.dispatchDate().text()), blankToNull(employment.position()),
                blankToNull(employment.titleSkill()), blankToNull(employment.titleObtainedDate().text()));
        }
        writeSummary(personId, "FULL_TIME", d.fullTimeEducation());
        writeSummary(personId, "IN_SERVICE", d.inServiceEducation());
        // raw_text 与 sort_order 是原样导出的依据，不能只保存解析后的开始/结束日期。
        if (d.mainEducation() != null) d.mainEducation().forEach(item -> mapper.insertEducation(properties.tenantId(), personId,
            "MAIN", null, null, null, null, item.startDate(), item.endDate(), item.rawText(), item.sortOrder() + 1));
        if (d.workExperiences() != null) d.workExperiences().forEach(item ->
            mapper.insertWork(properties.tenantId(), personId, item.startDate(), item.endDate(), blankToNull(item.rightText()),
                item.rawText(), item.sortOrder() + 1));
        if (d.rewardsPunishments() != null) d.rewardsPunishments().forEach(item ->
            mapper.insertReward(properties.tenantId(), personId, item.startDate(), item.rawText(), item.sortOrder() + 1));
        if (d.familyMembers() != null) d.familyMembers().forEach(member -> {
            String memberName = cleanOptional(member.name());
            String relationship = cleanOptional(member.relationship());
            mapper.insertFamily(properties.tenantId(), personId, relationship, memberName, NameNormalizer.normalize(memberName),
                blankToNull(member.birthDate().text()), blankToNull(member.ageText()),
                blankToNull(member.politicalStatus()), blankToNull(member.workplacePosition()), familyRaw(member), member.sortOrder() + 1);
        });
        if (d.photo() != null) {
            // 照片以原始 BLOB 保存；哈希、尺寸、MIME 和锚点用于校验及无损重建，不转 Base64。
            ArchiveDocument.PhotoAnchor anchor = d.photo().anchor();
            mapper.insertPhoto(properties.tenantId(), personId, d.photo().bytes(), d.photo().mimeType(),
                d.photo().originalFilename(), d.photo().bytes().length, d.photo().width(), d.photo().height(), sha256(d.photo().bytes()),
                anchor.col1(), anchor.row1(), anchor.dx1(), anchor.dy1(), anchor.col2(), anchor.row2(),
                anchor.dx2(), anchor.dy2(), anchor.anchorType());
        }
    }

    private void writeSummary(long personId, String type, ArchiveDocument.EducationSummary summary) {
        if (summary == null) return;
        mapper.insertEducation(properties.tenantId(), personId, type, blankToNull(summary.school()), blankToNull(summary.major()),
            blankToNull(summary.education()), blankToNull(summary.degree()), null, null, null, 0);
    }

    private static OrganizationDepartment splitOrganizationDepartment(String raw) {
        String value = blankToNull(raw);
        if (value == null) return new OrganizationDepartment(null, null);
        int separator = value.indexOf(" / ");
        if (separator <= 0 || separator + 3 >= value.length()) return new OrganizationDepartment(value, null);
        return new OrganizationDepartment(blankToNull(value.substring(0, separator)),
            blankToNull(value.substring(separator + 3)));
    }

    private record OrganizationDepartment(String organization, String department) {}

    private static String familyRaw(ArchiveDocument.FamilyMember m) {
        return String.join("\t", nullToEmpty(m.relationship()), nullToEmpty(m.name()),
            m.birthDate().text(), nullToEmpty(m.ageText()), nullToEmpty(m.politicalStatus()),
            nullToEmpty(m.workplacePosition()));
    }

    private static String cleanOptional(String value) { return value == null ? "" : value.strip(); }
    private static String blankToNull(String value) { return value == null || value.strip().isEmpty() ? null : value.strip(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record PreparedArchive(ArchiveDocument document, String employeeNo, String name,
                                  String normalizedName, String identityCard, byte[] identityHmac) {}
}
