import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { EventPhoto, relativeApiUrl } from './photo.models';

/** Photos d'un événement. Chemins relatifs : fonctionne sous n'importe quel base href. */
@Injectable({ providedIn: 'root' })
export class PhotoService {
  private readonly http = inject(HttpClient);

  /** Galerie de l'événement, la plus récente d'abord. */
  list(eventId: string): Observable<EventPhoto[]> {
    return this.http.get<EventPhoto[]>(`api/events/${encodeURIComponent(eventId)}/photos`);
  }

  /** Octets de la miniature (route protégée : chargée via HttpClient pour porter le jeton). */
  thumbnail(photo: EventPhoto): Observable<Blob> {
    return this.http.get(relativeApiUrl(photo.thumbnailUrl), { responseType: 'blob' });
  }

  /** Envoi multipart (partie « file »). */
  upload(eventId: string, file: File): Observable<EventPhoto> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<EventPhoto>(`api/events/${encodeURIComponent(eventId)}/photos`, body);
  }
}
