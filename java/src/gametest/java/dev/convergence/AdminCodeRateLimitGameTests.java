package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real membership files; no live worlds, external accounts, or operator commands. */
public class AdminCodeRateLimitGameTests {
    static final long WINDOW=Memberships.ADMIN_CODE_GLOBAL_WINDOW_MS;
    static final int LIMIT=Memberships.ADMIN_CODE_GLOBAL_FAILURE_LIMIT;

    private static Memberships open(GameTestHelper c,Path file,Path config) {
        var s=new Memberships(c.getLevel().getServer(),file,config);
        s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        return s;
    }

    @GameTest public void globalCodeFailuresBlockDifferentAccountsAcrossRestart(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=open(c,file,config);
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",1000),"A wrong code is rejected");
        var valid=UUID.randomUUID();
        c.assertTrue(s.redeem(valid,"test-secret",1001),"Correct code still works before the shared threshold");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),1,"Success does not erase shared failures");
        for(int i=1;i<LIMIT;i++)
            c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",1001+i),"Each different account's bad code is rejected");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),LIMIT,"Shared failures are bounded");
        var blocked=UUID.randomUUID();
        c.assertFalse(s.redeem(blocked,"test-secret",1011),"Shared lock blocks even a correct code from another account");
        c.assertFalse(s.data.accounts.containsKey(blocked.toString()),"Blocked attempt does not create an account");
        var reload=open(c,file,config);
        c.assertFalse(reload.redeem(blocked,"test-secret",1012),"Shared lock survives a normal restart");
        c.assertFalse(reload.redeem(blocked,"test-secret",999),"A backwards clock cannot clear the lock");
        c.assertFalse(reload.redeem(blocked,"test-secret",1009+WINDOW),"Lock holds until 15 minutes after the tenth failure");
        c.assertTrue(reload.redeem(blocked,"test-secret",1010+WINDOW),"Code works after the shared window expires");
        c.assertTrue(reload.account(blocked).admin,"Successful redemption retains normal Admin behavior");
        c.succeed();
    }

    @GameTest public void burstSpanningOldFixedWindowBoundaryLocks(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-span-");
        var s=open(c,dir.resolve("members.json"),dir.resolve("config.json"));
        // The old fixed window started here and reset at 1000+WINDOW, splitting the burst below.
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",1000),"Window-opening failure");
        for(int i=0;i<5;i++)s.redeem(UUID.randomUUID(),"wrong",1000+WINDOW-5+i);
        c.assertValueEqual(s.data.adminCodeGlobalLockedUntil,0L,"Six failures do not lock");
        for(int i=0;i<4;i++)s.redeem(UUID.randomUUID(),"wrong",1000+WINDOW+i);
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),9,"The first failure expired exactly at its window boundary");
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",1000+WINDOW+4),"Nine live failures still allow a correct code");
        long tenth=1000+WINDOW+5;
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",tenth),"Tenth failure in a rolling window");
        c.assertValueEqual(s.data.adminCodeGlobalLockedUntil,tenth+WINDOW,"Lock lasts 15 minutes after the tenth failure");
        var blocked=UUID.randomUUID();
        c.assertFalse(s.redeem(blocked,"test-secret",tenth+1),"A burst spanning the old boundary blocks correct codes");
        c.assertFalse(s.redeem(blocked,"test-secret",tenth+WINDOW-1),"Still blocked just before expiry");
        c.assertTrue(s.redeem(blocked,"test-secret",tenth+WINDOW),"Redemption resumes at expiry");
        c.succeed();
    }

    @GameTest public void rollingFailuresExpireNormallyWithoutLocking(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-expiry-");
        var s=open(c,dir.resolve("members.json"),dir.resolve("config.json"));
        for(int i=0;i<LIMIT-1;i++)s.redeem(UUID.randomUUID(),"wrong",5000+i);
        // Each new failure arrives just after one older failure leaves the window.
        for(int i=0;i<LIMIT-1;i++)
            c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",5000+WINDOW+i),"Spaced failures are rejected");
        c.assertValueEqual(s.data.adminCodeGlobalLockedUntil,0L,"Never ten failures inside one window");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),LIMIT-1,"Expired failures are pruned");
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",5000+WINDOW+LIMIT),"Correct code works below the limit");
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",5000+3*WINDOW),"Correct code works after all failures expire");
        c.assertTrue(s.data.adminCodeFailureTimes.isEmpty(),"All failures expired");
        c.succeed();
    }

    @GameTest public void rollingHistorySurvivesRestartWithoutSecrets(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-restart-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=open(c,file,config);
        for(int i=0;i<5;i++)s.redeem(UUID.randomUUID(),"guess-"+i,7000+i);
        var json=Files.readString(file);
        c.assertFalse(json.contains("guess-")||json.contains(Memberships.hash("guess-0")),"Guesses are never persisted");
        var reload=open(c,file,config);
        c.assertValueEqual(reload.data.adminCodeFailureTimes.size(),5,"Rolling history reloads");
        c.assertValueEqual(reload.data.adminCodeLastFailureAt,7004L,"High-water mark reloads");
        for(int i=0;i<5;i++)reload.redeem(UUID.randomUUID(),"wrong",8000+i);
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",8005),"Failures before and after restart combine");
        var again=open(c,file,config);
        c.assertValueEqual(again.data.adminCodeGlobalLockedUntil,8004+WINDOW,"Lock reloads from the rolling history");
        c.succeed();
    }

    @GameTest public void backwardClockFailsClosed(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-clock-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=open(c,file,config);
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",50_000),"Failure recorded");
        var other=UUID.randomUUID();
        c.assertFalse(s.redeem(other,"test-secret",49_999),"A clock behind the last failure blocks correct codes");
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",49_999),"A clock behind the last failure records nothing");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),1,"Rejected rollback attempt is not counted");
        c.assertFalse(s.data.accounts.containsKey(other.toString()),"Rollback rejection does not create an account");
        // After expiry pruning, the high-water mark still detects a rollback.
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",50_000+WINDOW),"Old failure expired normally");
        c.assertTrue(s.data.adminCodeFailureTimes.isEmpty(),"Expired failure pruned");
        var reload=open(c,file,config);
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",50_000-1),"Rollback detection survives pruning and restart");
        c.assertTrue(reload.redeem(UUID.randomUUID(),"test-secret",50_000),"Clock at the high-water mark is accepted");
        c.succeed();
    }

    @GameTest public void legacyFixedWindowMigratesWithoutWeakening(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-migrate-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        Files.writeString(file,"{\"format\":1,\"accounts\":{},\"adminCodeGlobalFailures\":9,"
            +"\"adminCodeGlobalWindowStarted\":1000,\"adminCodeGlobalLockedUntil\":0}");
        var s=open(c,file,config);
        c.assertValueEqual(s.data.adminCodeGlobalFailures,0,"Legacy counter migrates at load");
        c.assertValueEqual(s.data.adminCodeLastFailureAt,1000+WINDOW-1,"Latest legacy failure is the last instant of its window");
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",999),"A clock before the legacy window fails closed");
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",1000+WINDOW-2),"A clock before the inferred latest failure fails closed");
        // The old code reset here; the unknown later failures are treated as live.
        long now=1000+WINDOW+10;
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",now),"Failure after the old reset point");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),LIMIT-1,"Only the window-opening legacy failure is provably expired");
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",now+1),"Tenth conservative failure");
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",now+2),"Migrated failures still contribute to the shared lock");
        var reload=open(c,file,config);
        c.assertValueEqual(reload.data.adminCodeGlobalFailures,0,"Migration was saved");
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",now+3),"Migrated lock survives restart");
        c.succeed();
    }

    @GameTest public void legacyActiveLockMigratesAndExpiresOnTime(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-migrate-lock-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        long tenth=1009;
        Files.writeString(file,"{\"format\":1,\"accounts\":{},\"adminCodeGlobalFailures\":10,"
            +"\"adminCodeGlobalWindowStarted\":1000,\"adminCodeGlobalLockedUntil\":"+(tenth+WINDOW)+"}");
        var s=open(c,file,config);
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",tenth+1),"Legacy active lock still blocks");
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",tenth+WINDOW-1),"Legacy lock is not shortened");
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",tenth+WINDOW),"Legacy lock ends at its saved expiry");
        c.assertTrue(s.data.adminCodeFailureTimes.isEmpty(),"Legacy failures end no later than the saved lock");
        c.succeed();
    }

    @GameTest public void legacyRollbackWithinWindowCannotExpireFailuresEarly(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-migrate-rollback-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        // Nine legacy failures may really have happened as late as t=100000; the old file only proves the start.
        long actualLast=100_000;
        Files.writeString(file,"{\"format\":1,\"accounts\":{},\"adminCodeGlobalFailures\":9,"
            +"\"adminCodeGlobalWindowStarted\":1000,\"adminCodeGlobalLockedUntil\":0}");
        var s=open(c,file,config);
        long inferred=1000+WINDOW-1;
        c.assertFalse(s.redeem(UUID.randomUUID(),"test-secret",2000),"A clock rolled back inside the legacy window blocks correct codes");
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",2000),"A rolled-back wrong guess is rejected");
        c.assertValueEqual(s.data.adminCodeFailureTimes.size(),LIMIT-1,"The rolled-back guess is not recorded");
        // An unrelated membership save while the clock is behind must not persist an earlier placement.
        s.save();
        var reload=open(c,file,config);
        c.assertValueEqual(reload.data.adminCodeLastFailureAt,inferred,"The saved migration keeps the inferred latest time");
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",actualLast),"Still blocked at the actual last failure time");
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",inferred-1),"Still blocked just before the inferred time");
        // The window-opening failure has expired; the eight inferred late failures remain live.
        c.assertFalse(reload.redeem(UUID.randomUUID(),"wrong",1000+WINDOW),"First failure after the clock catches up");
        c.assertValueEqual(reload.data.adminCodeGlobalLockedUntil,0L,"Nine live failures do not lock");
        long beforeActualExpiry=actualLast+WINDOW-1;
        c.assertFalse(reload.redeem(UUID.randomUUID(),"wrong",beforeActualExpiry),"Tenth failure before the actual late failures expire");
        c.assertValueEqual(reload.data.adminCodeGlobalLockedUntil,beforeActualExpiry+WINDOW,"Legacy failures still counted until their real expiry");
        c.assertFalse(reload.redeem(UUID.randomUUID(),"test-secret",beforeActualExpiry+1),"Correct codes are blocked by the shared lock");
        c.succeed();
    }

    @GameTest public void legacySingleFailureIsExactAndLegacyLockSurvivesRollback(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-migrate-exact-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        Files.writeString(file,"{\"format\":1,\"accounts\":{},\"adminCodeGlobalFailures\":1,"
            +"\"adminCodeGlobalWindowStarted\":1000,\"adminCodeGlobalLockedUntil\":0}");
        var s=open(c,file,config);
        c.assertValueEqual(s.data.adminCodeLastFailureAt,1000L,"A single legacy failure is exactly the window start");
        c.assertTrue(s.redeem(UUID.randomUUID(),"test-secret",1001),"No extra rollback block when the only failure is known");
        var lockDir=Files.createTempDirectory("infinity-admin-rate-migrate-lockback-");
        var lockFile=lockDir.resolve("members.json");long tenth=5000;
        Files.writeString(lockFile,"{\"format\":1,\"accounts\":{},\"adminCodeGlobalFailures\":10,"
            +"\"adminCodeGlobalWindowStarted\":1000,\"adminCodeGlobalLockedUntil\":"+(tenth+WINDOW)+"}");
        var locked=open(c,lockFile,lockDir.resolve("config.json"));
        c.assertValueEqual(locked.data.adminCodeLastFailureAt,tenth,"A consistent legacy lock proves the tenth failure time");
        c.assertFalse(locked.redeem(UUID.randomUUID(),"test-secret",2000),"Rollback inside a locked legacy window stays blocked");
        c.assertTrue(locked.redeem(UUID.randomUUID(),"test-secret",tenth+WINDOW),"The proven legacy lock ends on time");
        c.succeed();
    }

    @GameTest public void invalidRollingDataRefusesToLoad(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-invalid-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        String original="{\"format\":1,\"accounts\":{},\"adminCodeFailureTimes\":[5,4],\"adminCodeLastFailureAt\":5}";
        Files.writeString(file,original);
        boolean refused=false;
        try {new Memberships(c.getLevel().getServer(),file,config);}
        catch(IllegalStateException expected) {refused=true;}
        c.assertTrue(refused,"Unordered failure times must not load");
        c.assertValueEqual(Files.readString(file),original,"Invalid data is not overwritten");
        c.succeed();
    }

    @GameTest public void failedThrottleSaveRejectsFurtherCodesInThisProcess(GameTestHelper c) throws Exception {
        var dir=Files.createTempDirectory("infinity-admin-rate-failure-");
        var file=dir.resolve("members.json");var config=dir.resolve("config.json");
        var s=open(c,file,config);
        Files.createDirectory(dir.resolve("members.json.tmp"));
        c.assertFalse(s.redeem(UUID.randomUUID(),"wrong",2000),"Storage failure rejects the failed attempt");
        c.assertTrue(s.adminCodeStorageFailed,"Storage failure blocks subsequent redemption in this process");
        var blocked=UUID.randomUUID();
        c.assertFalse(s.redeem(blocked,"test-secret",2001),"A correct code cannot pass after the failed write");
        c.assertFalse(s.redeem(blocked,"test-secret",2000+3*WINDOW),"The storage block does not expire with time");
        c.assertFalse(s.data.accounts.containsKey(blocked.toString()),"Blocked attempt does not create an account");
        c.succeed();
    }
}
