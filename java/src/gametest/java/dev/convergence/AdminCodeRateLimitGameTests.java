package dev.convergence;

import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real membership files; no live worlds, external accounts, or operator commands. */
public class AdminCodeRateLimitGameTests {
    @GameTest public void globalCodeFailuresBlockDifferentAccountsAcrossRestart(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=new Memberships(c.getLevel().getServer(),file,config);
        s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",1000),"A wrong code is rejected");
        var valid=UUID.randomUUID();
        c.assertTrue(s.redeem(valid,"test-secret",1001),"Correct code still works before the shared threshold");
        c.assertValueEqual(s.data.adminCodeGlobalFailures,1,"Success does not erase shared failures");
        for(int i=1;i<Memberships.ADMIN_CODE_GLOBAL_FAILURE_LIMIT;i++)
            c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",1001+i),"Each different account's bad code is rejected");
        c.assertValueEqual(s.data.adminCodeGlobalFailures,Memberships.ADMIN_CODE_GLOBAL_FAILURE_LIMIT,"Shared failures are bounded");
        var blocked=UUID.randomUUID();
        c.assertFalse(s.redeem(blocked,"test-secret",1011),"Shared lock blocks even a correct code from another account");
        c.assertFalse(s.data.accounts.containsKey(blocked.toString()),"Blocked attempt does not create an account");
        var reload=new Memberships(s.server,file,config);
        reload.config.adminCodeEnabled=true;reload.config.adminCodeSha256=Memberships.hash("test-secret");
        c.assertFalse(reload.redeem(blocked,"test-secret",1012),"Shared lock survives a normal restart");
        c.assertFalse(reload.redeem(blocked,"test-secret",999),"A backwards clock cannot clear the lock");
        c.assertTrue(reload.redeem(blocked,"test-secret",1010+Memberships.ADMIN_CODE_GLOBAL_WINDOW_MS),"Code works after the shared window expires");
        c.assertTrue(reload.account(blocked).admin,"Successful redemption retains normal Admin behavior");
        c.succeed();
    }

    @GameTest public void failedThrottleSaveRejectsFurtherCodesInThisProcess(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-failure-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=new Memberships(c.getLevel().getServer(),file,config);
        s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        Files.createDirectory(dir.resolve("members.json.tmp"));
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",2000),"Storage failure rejects the failed attempt");
        c.assertTrue(s.adminCodeStorageFailed,"Storage failure blocks subsequent redemption in this process");
        var blocked=UUID.randomUUID();
        c.assertFalse(s.redeem(blocked,"test-secret",2001),"A correct code cannot pass after the failed write");
        c.assertFalse(s.data.accounts.containsKey(blocked.toString()),"Blocked attempt does not create an account");
        c.succeed();
    }
}
