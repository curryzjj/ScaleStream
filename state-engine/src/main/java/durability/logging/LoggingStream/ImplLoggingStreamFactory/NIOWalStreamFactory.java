package durability.logging.LoggingStream.ImplLoggingStreamFactory;

import common.collections.OsUtils;
import durability.logging.LoggingStream.LoggingStreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public class NIOWalStreamFactory implements LoggingStreamFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingStreamFactory.class);
    private final Path walPath;

    public NIOWalStreamFactory(String walPath, int partitionId) {
        String filePath = walPath + OsUtils.OS_wrapper(".wal_" + partitionId);
        this.walPath = Paths.get(filePath);
    }

    @Override
    public AsynchronousFileChannel createSnapshotStream() throws IOException {
        return AsynchronousFileChannel.open(walPath, WRITE, CREATE);
    }

    public Path getWalPath() {
        return walPath;
    }
}