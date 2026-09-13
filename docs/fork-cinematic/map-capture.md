# Separate Minecraft Singapore map

This is the cartographic miniature world **FORK - Singapore Map**, not the full-size gameplay district. Finish the uninterrupted A/rewind/B capture before changing worlds.

Main merges the named paths from media/edit/camera-paths-singapore-map.json into the installed camera file before client launch. Cinematic does not write the profile or world. Use these two names only in the separate map world.

Use the supplied spectator setup, FOV50, a16:9 game picture and16 render chunks. Main's manifest places the overhead camera at128,220,128 above landY67, looking straight down with yaw180 and pitch90. FOV50 frames the whole220x111.73block main-island miniature with margin. The gold district-centre marker is at145,70,173.

Human action: Start OBS Recording, run `/camera path play fork_map_overview`, let the six-second view finish, then Stop. The optional `/camera path play fork_map_reveal` is a five-second marker-to-island pullback. No hand flying. Actual loaded framing and smoothness must be reviewed from the recording.

Film shot4 remains7-10seconds: explicitly cut from the actual district camera to the separately recorded map. Keep one compact caption identifying map orientation and the accepted district size, plus the Natural Earth credit. Select the whole-island portion of the recording. Never imply that the miniature is whole-island1:1 gameplay.

The original map world is unchanged. Source archive SHA256:9B8D0AE9B0B003A21A0C6961A3A439769DA40AE40CBB9CCB7C1C05E60CCB1243. Source manifest:fork-singapore-map-v1,15238landcells,183.58approximate metres per block. Its source-marker centre is latitude1.283 longitude103.85. The expanded1024district coverage caption still needs World's accepted bounds and Main's final acceptance.
