# Review for MR !3224

**URL:** https://sourcery.assaabloy.net/amer/amer-aehg-software/software/dsr-group/assaabloy-dsr1/-/merge_requests/3224  
**Title:** feat(awl): GWLP-2301 DOW host schedule with calendar ref lifecycle  
**Author:** rooban.vairavan@assaabloy.com (roovai)  
**Target:** AWL_Active_Dev  
**Source:** feature-awl-GWLP-2301-host-schedule-calendar-refs  
**State:** opened | Draft: false  
**Changes:** 18 | Pipeline: success (1751996)  
**Reviewers:** vinoth.paulraj@assaabloy.com (vipaul)  
**Merge status:** can_be_merged (detailed: not_approved)

## Description
Orchestrate calendar before schedule on the lock, persist calendar and schedule refs (DB v52), bind DOW wake via connectionScheduling, and retain one calendar per lock (early calendar persist and UPDATE on stored ref).

## Review
### Scope & Design
- [ ] Verify calendar is orchestrated before schedule on lock (ordering change)
- [ ] Verify calendar and schedule refs persisted correctly (DB v52 migration/schema changes)
- [ ] Verify DOW wake binding via connectionScheduling
- [ ] Verify single calendar per lock - early persist + UPDATE on stored ref

### Code Quality
- [ ] Check for race conditions in calendar/schedule ref lifecycle
- [ ] Validate DB migration (v52) is backward compatible/safe
- [ ] Ensure proper error handling for orchestration failures
- [ ] Check logging/telemetry for new flows

### Testing & CI
- [x] Pipeline passed (success)
- [ ] Unit/integration tests updated for new lifecycle
- [ ] Manual verification of DOW host schedule behavior

### Security/Compatibility
- [ ] No breaking changes to existing schedule/calendar APIs
- [ ] Proper validation of refs
- [ ] Compatible with existing lock configurations

## Recommendation
**Changes requested / Pending approval** - Pipeline passes but MR not approved. Need to review actual diff (18 files) for correctness of orchestration order, DB migration v52, and ref lifecycle logic. Recommend thorough testing of DOW scheduling edge cases.

*Generated with token-based API access.*
