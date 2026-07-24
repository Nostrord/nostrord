// Dev-server SPA fallback: deep-link paths like /naddr1... must serve index.html so the
// app boots and rewrites them to the #/g/ hash route. Production equivalent: sw.js serves
// index.html on 404 + 404.html's ?/ redirect for the first, uncontrolled visit.
if (config.devServer) {
    config.devServer.historyApiFallback = true
}
