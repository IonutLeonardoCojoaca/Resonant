package com.example.resonant.recap

/** Ejemplos JSON del contrato (design.md de add-monthly-recap). */
object RecapSamples {
    const val LIST = """
        [ { "periodKey": "2026-10", "seen": false, "generatedAt": "2026-11-01T02:05:12.000Z" },
          { "periodKey": "2026-09", "seen": true,  "generatedAt": "2026-10-01T01:04:40.000Z" } ]
    """

    const val DETAIL = """
        { "periodKey": "2026-09", "year": 2026, "month": 9, "monthName": "septiembre",
          "seen": true, "generatedAt": "2026-10-01T01:04:40.000Z",
          "ariaText": "Septiembre empezó con indie tranquilo y acabó en metalcore.",
          "ariaOrigin": "aria", "playlistId": null,
          "cards": [
            { "type": "minutes", "minutes": 842, "previousMinutes": 610, "changePercent": 38 },
            { "type": "top_songs", "songs": [
                { "rank": 1, "plays": 31, "minutes": 104,
                  "song": { "id": "s1", "title": "Like a Villain", "imageUrl": "https://x/s1.jpg",
                            "artists": [ { "id": "a1", "name": "Bad Omens" } ] } },
                { "rank": 3, "plays": 20, "minutes": 70,
                  "song": { "id": "s3", "title": "Just Pretend", "artists": [ { "id": "a1", "name": "Bad Omens" } ] } } ] },
            { "type": "top_artists", "artists": [
                { "rank": 1, "plays": 58, "minutes": 190, "artist": { "id": "a1", "name": "Bad Omens" } } ],
              "newArtist": { "plays": 7, "minutes": 26, "artist": { "id": "a9", "name": "Spiritbox" } } },
            { "type": "genres", "top": { "id": "g1", "name": "Metalcore", "gradientColors": "#000,#fff", "share": 0.34 },
              "rising": { "id": "g2", "name": "Indie", "gradientColors": null, "share": 0.18, "previousShare": 0.05 } },
            { "type": "moment", "slot": "night", "share": 0.41 },
            { "type": "streak", "longestStreakDays": 9, "daysListened": 24, "daysInMonth": 30 },
            { "type": "unisound", "sessions": 3, "people": 2 },
            { "type": "aria", "text": "Septiembre empezó con…", "origin": "aria" },
            { "type": "playlist", "name": "Tu septiembre 2026", "songCount": 50, "playlistId": null }
          ] }
    """

    const val PLAYLIST = """{ "playlistId": "p1", "name": "Tu septiembre 2026", "songCount": 50 }"""
}
