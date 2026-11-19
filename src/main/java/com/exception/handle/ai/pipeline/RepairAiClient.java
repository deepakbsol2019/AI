package com.exception.handle.ai.pipeline;

import com.exception.handle.ai.model.RepairData.PatchProposal;
import com.exception.handle.ai.model.RepairData.RepairInput;

public interface RepairAiClient {

	PatchProposal suggest(RepairInput input) throws Exception;
}
