# Start/Sit news fetch within a review

Injury headlines and public analysis use the same RSS feed. A lineup review
now shares one feed outcome between those two interpretations. Previously, a
failed injury-news fetch could be repeated for analysis later in the same
review, adding a second network wait for the same unavailable feed.

Each recommendation call creates a new review view. A failed or interrupted
fetch is retained only within that call; the next review can try again.
The existing five-minute successful-feed cache remains. An expired successful
feed is not used when its refresh fails. Injury and analysis parsing stay
separate, and their existing source, age and ambiguity rules still apply.

This removes one duplicate request in the failed-feed path. It does not claim
an overall latency target or make a timed-out lineup review successful.
Projection, availability, game-lock, workload and player holds remain in force.
No Sleeper transaction is added, and missing optional news is not clearance.
