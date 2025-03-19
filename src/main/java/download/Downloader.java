package download;

import java.io.IOException;

public interface Downloader {
    void downloadFile(String remotePath, String localPath, boolean resume) throws IOException;
    void uploadFile(String localPath, boolean resume) throws IOException;
}
