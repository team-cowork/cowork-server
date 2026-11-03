package permission

import "testing"

func TestMediaPermissions(t *testing.T) {
	tests := []struct {
		name                                    string
		known, speak, video, liveRoom, liveHost bool
		want                                    MediaAccess
	}{
		{"voice listener retains data", true, false, false, false, false, MediaAccess{Subscribe: true, PublishData: true}},
		{"voice speaking does not grant video", true, true, false, false, false, MediaAccess{Subscribe: true, PublishData: true, Microphone: true}},
		{"voice video grants camera and screen together", true, false, true, false, false, MediaAccess{Subscribe: true, PublishData: true, Camera: true, ScreenShare: true}},
		{"live viewer without video remains subscribe-only", true, true, false, true, false, MediaAccess{Subscribe: true}},
		{"live viewer video grants camera only", true, true, true, true, false, MediaAccess{Subscribe: true, Camera: true}},
		{"live host video grants camera and screen without data", true, true, true, true, true, MediaAccess{Subscribe: true, Microphone: true, ScreenShare: true, Camera: true}},
		{"live host without video is denied camera", true, true, false, true, true, MediaAccess{Subscribe: true, Microphone: true}},
		{"unknown voice access disables all media and data", false, true, true, false, false, MediaAccess{}},
		{"unknown live access disables all media and data", false, true, true, true, true, MediaAccess{}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := MediaAccessFor(tt.known, tt.speak, tt.video, tt.liveRoom, tt.liveHost); got != tt.want {
				t.Fatalf("media access = %+v, want %+v", got, tt.want)
			}
		})
	}
}
