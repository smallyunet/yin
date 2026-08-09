package org.yinwang.yin;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Web3TransactionGuardDemoTest {
    private static final Path PROGRAM = Path.of("examples/web3/transaction-guard/main.yin");

    @Test void maintainedFixturesCoverWeb3PolicyAndBoundaryPaths() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("approve.json", "{\"tag\":\"Approve\",\"requestId\":\"tx-approve\",\"reason\":\"simulation and policy checks passed\"}");
        expected.put("unlimited-approval.json", "{\"tag\":\"NeedsApproval\",\"requestId\":\"tx-unlimited\",\"risk\":\"high\",\"reason\":\"unlimited token approval requested\"}");
        expected.put("high-value.json", "{\"tag\":\"NeedsApproval\",\"requestId\":\"tx-high-value\",\"risk\":\"medium\",\"reason\":\"transaction exceeds the automatic USD limit\"}");
        expected.put("simulation-failed.json", "{\"tag\":\"Reject\",\"requestId\":\"tx-simulation\",\"code\":\"simulation-failed\",\"reason\":\"transaction simulation did not succeed\"}");
        expected.put("unverified-contract.json", "{\"tag\":\"Reject\",\"requestId\":\"tx-unverified\",\"code\":\"unverified-contract\",\"reason\":\"target contract is not verified\"}");
        expected.put("unsupported-chain.json", "{\"tag\":\"Reject\",\"requestId\":\"tx-chain\",\"code\":\"unsupported-chain\",\"reason\":\"chain is outside the configured policy\"}");
        expected.put("contract-upgrade.json", "{\"tag\":\"NeedsApproval\",\"requestId\":\"tx-upgrade\",\"risk\":\"critical\",\"reason\":\"contract upgrades require human approval\"}");
        expected.put("invalid-address.json", "{\"tag\":\"Reject\",\"requestId\":\"tx-address\",\"code\":\"invalid-address\",\"reason\":\"target must be a 20-byte 0x address\"}");
        expected.put("empty-address.json", "{\"tag\":\"Reject\",\"requestId\":\"tx-empty-address\",\"code\":\"invalid-address\",\"reason\":\"target must be a 20-byte 0x address\"}");
        expected.put("wrong-type.json", "{\"tag\":\"Reject\",\"requestId\":\"invalid-request\",\"code\":\"wrong-type\",\"reason\":\"$.valueUsd: expected finite number, got string\"}");

        for (Map.Entry<String, String> fixture : expected.entrySet()) {
            String input = Files.readString(
                    Path.of("examples/web3/transaction-guard/inputs", fixture.getKey()),
                    StandardCharsets.UTF_8);
            Run result = runJson(input);
            assertEquals(0, result.status, fixture.getKey() + " stderr: " + result.error);
            assertEquals(fixture.getValue() + "\n", result.output, fixture.getKey());
            assertEquals("", result.error, fixture.getKey());
        }
    }

    @Test void transactionGuardHasAClosedJsonBoundaryType() {
        String type = new TypeChecker(PROGRAM.toString()).typecheck(PROGRAM.toString()).toString();
        assertEquals("(Result String (record EncodeError [code String] [path String] [message String]))", type);
    }

    private static Run runJson(String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = Interpreter.runJson(
                new String[]{PROGRAM.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                () -> input);
        return new Run(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Run(int status, String output, String error) { }
}
