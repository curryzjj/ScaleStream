package durability.recovery;

import common.collections.OsUtils;
import common.io.LocalFS.LocalDataInputStream;
import common.tools.Deserialize;
import durability.logging.LoggingResult.LoggingCommitInformation;
import durability.snapshot.SnapshotResult.SnapshotCommitInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.lib.ConcurrentHashMap;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecoveryHelperProvider {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryHelperProvider.class);
    public static SnapshotCommitInformation getLatestCommitSnapshotCommitInformation(File recoveryFile) throws IOException {
        List<SnapshotCommitInformation> commitInformation = new ArrayList<>();
        LocalDataInputStream inputStream = new LocalDataInputStream(recoveryFile);
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        try{
            while(true){
                int len = dataInputStream.readInt();
                byte[] lastSnapResultBytes = new byte[len];
                dataInputStream.readFully(lastSnapResultBytes);
                SnapshotCommitInformation SnapshotCommitInformation = (SnapshotCommitInformation) Deserialize.Deserialize(lastSnapResultBytes);
                commitInformation.add(SnapshotCommitInformation);
            }
        } catch (EOFException e){
            LOG.info("finish read the current.log");
        } finally {
            dataInputStream.close();
        }
        return commitInformation.get(commitInformation.size() - 1);
    }
    public static void getCommittedLogMetaData(File recoveryFile, List<LoggingCommitInformation> committedMetaData) throws IOException {
        LocalDataInputStream inputStream = new LocalDataInputStream(recoveryFile);
        try (DataInputStream dataInputStream = new DataInputStream(inputStream)) {
            while (true) {
                int len = dataInputStream.readInt();
                byte[] metaDataBytes = new byte[len];
                dataInputStream.readFully(metaDataBytes);
                LoggingCommitInformation loggingCommitInformation = (LoggingCommitInformation) Deserialize.Deserialize(metaDataBytes);
                assert loggingCommitInformation != null;
                committedMetaData.add(loggingCommitInformation);
            }
        } catch (EOFException e) {
            LOG.info("finish read the current.log");
        }
    }
    public static void getLastTask(long[] lastTasks, String outputStoreRootPath) throws IOException {
        for (int i = 0; i < lastTasks.length; i ++) {
            File file = new File(outputStoreRootPath + OsUtils.OS_wrapper(i + ".output"));
            LocalDataInputStream inputStream = new LocalDataInputStream(file);
            DataInputStream dataInputStream = new DataInputStream(inputStream);
            lastTasks[i] = dataInputStream.readLong();
        }
    }
}