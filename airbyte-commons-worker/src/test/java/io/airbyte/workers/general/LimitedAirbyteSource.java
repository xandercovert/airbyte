package io.airbyte.workers.general;

import io.airbyte.config.WorkerSourceConfig;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.workers.internal.AirbyteSource;
import io.airbyte.workers.test_utils.AirbyteMessageUtils;
import java.nio.file.Path;
import java.util.Optional;

public class LimitedAirbyteSource implements AirbyteSource {
  private static final int TOTAL_RECORDS = 5_000_000;

  private int currentRecords = 0;

  @Override
  public void start(WorkerSourceConfig sourceConfig, Path jobRoot) throws Exception {

  }

  @Override
  public boolean isFinished() {
    return currentRecords == TOTAL_RECORDS;
  }

  @Override
  public int getExitValue() {
    return 0;
  }

  @Override
  public Optional<AirbyteMessage> attemptRead() {
    currentRecords++;
    return Optional.of(AirbyteMessageUtils.createRecordMessage("s1", "data",
        "This is a fairly long sentence to provide some bytes here. More bytes is better as it helps us measure performance."
            + "Random append to prevent dead code optimisation: " + currentRecords));
  }

  @Override
  public void close() throws Exception {

  }

  @Override
  public void cancel() throws Exception {

  }
}
