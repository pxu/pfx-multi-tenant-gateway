// A slow upstream: every response waits a random 100 ms to 5 s (uniform), then answers "ok".
// njs timers do not hold a worker, so one nginx can keep thousands of such requests waiting.
function delayed(r) {
    var ms = 100 + Math.floor(Math.random() * 4901);
    setTimeout(function () {
        r.return(200, "ok\n");
    }, ms);
}

export default { delayed };
