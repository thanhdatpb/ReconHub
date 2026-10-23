package vn.reconhub.core.files;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.SourceRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a file on disk into an {@link ImportFile} row. The SHA-256 checksum guarantees a file is loaded once,
 * whether it arrives twice over SFTP or someone uploads it again.
 */
@Service
public class FileRegistrar {
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DM_HM = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final ImportFileRepository files;
    private final SourceRepository sources;
    private final ReconProperties props;
    private final Clock clock;

    public FileRegistrar(ImportFileRepository files, SourceRepository sources, ReconProperties props, Clock clock) {
        this.files = files;
        this.sources = sources;
        this.props = props;
        this.clock = clock;
    }

    public record Registered(ImportFile file, boolean duplicate) {}

    public Optional<Source> detectSource(String fileName) {
        return sources.findAll().stream().filter(s -> fileName.matches(s.getFileRegex())).findFirst();
    }

    /** The business date is the yyyyMMdd stamp in the file name, or today when there is none. */
    public LocalDate businessDate(Source source, String fileName) {
        Matcher m = Pattern.compile(source.getFileRegex()).matcher(fileName);
        if (m.matches() && m.groupCount() >= 1) {
            try {
                return LocalDate.parse(m.group(1), YYYYMMDD);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return LocalDate.now(clock);
    }

    /**
     * Registers a file: duplicates are recorded and moved to /data/rejected; new files are moved to /data/archive
     * and left in PROCESSING for the ingestion job.
     */
    @Transactional
    public Registered register(Path file, Source source, String originalName, Instant receivedAt, String uploadedBy) {
        String checksum = sha256(file);
        LocalDate businessDate = businessDate(source, originalName);
        Optional<ImportFile> existing = files.findFirstByChecksumAndStatusNot(checksum, FileStatus.DUPLICATE);
        if (existing.isPresent()) {
            ImportFile first = existing.get();
            Path rejected = move(file, props.getData().rejected().resolve(checksum.substring(0, 8) + "_" + originalName));
            ImportFile dup = new ImportFile(source.getCode(), originalName, rejected.toString(), receivedAt, businessDate, checksum, FileStatus.DUPLICATE, uploadedBy);
            ZoneId zone = clock.getZone();
            dup.setMessage("Trùng checksum với " + first.getFileName() + " (nhận lúc " + DM_HM.format(first.getReceivedAt().atZone(zone)) + "). Không nạp lại.");
            return new Registered(files.save(dup), true);
        }
        Path archived = move(file, props.getData().archive().resolve(businessDate.toString().substring(0, 7))
                .resolve(checksum.substring(0, 8) + "_" + originalName));
        ImportFile f = new ImportFile(source.getCode(), originalName, archived.toString(), receivedAt, businessDate, checksum, FileStatus.PROCESSING, uploadedBy);
        return new Registered(files.save(f), false);
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path move(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            return Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
