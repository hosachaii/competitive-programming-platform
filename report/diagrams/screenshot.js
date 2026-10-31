// Captures the UI screenshots used in the report.
// Prereq: the stack is running and prep_demo_data.sh has written demo_data.json.
// Usage: cd report/diagrams && node screenshot.js
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const data = JSON.parse(fs.readFileSync(path.join(__dirname, 'demo_data.json'), 'utf8'));
const BASE = data.base;
const OUT = path.join(__dirname, '..', 'images');
const VIEWPORT = { width: 1440, height: 900 };

async function main() {
  const browser = await chromium.launch();

  async function ctxFor(auth) {
    const ctx = await browser.newContext({
      viewport: VIEWPORT,
      deviceScaleFactor: 2,
      permissions: ['clipboard-read', 'clipboard-write'],
    });
    if (auth) {
      await ctx.addInitScript((a) => {
        localStorage.setItem('accessToken', a.token);
        localStorage.setItem('refreshToken', a.refresh);
        localStorage.setItem('user', JSON.stringify(a.user));
      }, auth);
    }
    return ctx;
  }

  async function shot(ctx, route, file, { full = false, prep = null, waitFor = null } = {}) {
    const page = await ctx.newPage();
    await page.goto(BASE + route, { waitUntil: 'networkidle' });
    if (waitFor) { try { await page.waitForSelector(waitFor, { timeout: 15000 }); } catch (e) {} }
    if (prep) await prep(page);
    await page.waitForTimeout(900);
    await page.screenshot({ path: path.join(OUT, file), fullPage: full });
    console.log('saved', file);
    await page.close();
  }

  const anon = await ctxFor(null);
  const alice = await ctxFor(data.alice);
  const admin = await ctxFor(data.admin);

  // Logged-out pages
  await shot(anon, '/login', 'ui_login.png', { waitFor: 'form, input' });
  await shot(anon, '/register', 'ui_register.png', { waitFor: 'form, input' });

  // User pages
  await shot(alice, '/', 'ui_problem_list.png', { waitFor: 'table, a' });
  await shot(alice, '/leaderboard', 'ui_leaderboard.png', { waitFor: 'table' });

  // Problem detail with a solution typed into the Monaco editor
  await shot(alice, '/problems/' + data.problemSlug, 'ui_problem_detail.png', {
    full: true,
    waitFor: '.monaco-editor',
    prep: async (page) => {
      await page.waitForTimeout(1200);
      const editor = page.locator('.monaco-editor').first();
      await editor.click();
      await page.keyboard.press('Control+A');
      await page.keyboard.press('Delete');
      // paste via clipboard so Monaco inserts the code verbatim (no auto-indent staircase)
      await page.evaluate((code) => navigator.clipboard.writeText(code), data.aceCode);
      await page.keyboard.press('Control+V');
      await page.waitForTimeout(600);
      await page.mouse.wheel(0, -2000); // scroll back to top for the capture
      await page.waitForTimeout(300);
    },
  });

  // Submission pages (terminal verdicts already stored)
  await shot(alice, '/submissions/' + data.acId, 'ui_submission_accepted.png', { full: true, waitFor: '.badge' });
  await shot(alice, '/submissions/' + data.ceId, 'ui_submission_compile_error.png', { full: true, waitFor: '.badge' });
  await shot(alice, '/submissions/' + data.tleId, 'ui_submission_tle.png', { full: true, waitFor: '.badge' });

  // Admin pages
  await shot(admin, '/admin/new-problem', 'ui_admin_new_problem.png', { full: true, waitFor: 'input, form' });
  await shot(admin, '/admin/edit-problem/' + data.editProblemId, 'ui_admin_edit_problem.png', { full: true, waitFor: 'input, form' });

  await browser.close();
  console.log('all screenshots done');
}

main().catch((e) => { console.error(e); process.exit(1); });
